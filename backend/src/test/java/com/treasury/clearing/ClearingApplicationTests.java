package com.treasury.clearing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

/**
 * Full-stack acceptance on the seeded dataset:
 * - NA-CNY  : 8 open claims (2 rings) collapse to 2 real cash legs,
 *             pledged + disputed excluded;
 * - NA-NOFF : closed ring preserved as 3 original debts;
 * - NA-XCCY : mixed-currency ring nets into CNY with rate/time/diff recorded;
 * - lifecycle: SIMULATED -> CONFIRMED -> PAID_SIMULATED, claims only settle on confirm.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClearingApplicationTests {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fullAcceptanceScenario() throws Exception {
        JsonNode sim = simulate(LocalDate.of(2026, 9, 30));

        // NA-CNY: 8 incoming (6 open rings + pledged + disputed); 6 included, 2 excluded;
        // the two rings (gross 2600) collapse to exactly 2 real legs worth 600.
        JsonNode cny = group(sim, "NA-CNY");
        assertThat(cny.get("passThrough").asBoolean()).isFalse();
        assertThat(cny.get("originalLegCount").asInt()).isEqualTo(6);
        assertThat(cny.get("exclusions").size()).isEqualTo(2);
        assertThat(reasons(cny)).containsExactlyInAnyOrder("PLEDGED", "DISPUTED");
        long cnyCashLegs = cashLegs(cny);
        assertThat(cnyCashLegs).isEqualTo(2);
        // Compare numerically: JsonNode.asText() normalizes trailing zeros (600.00 -> 600.0).
        assertThat(cny.get("netAmount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(cny.get("grossAmount").decimalValue()).isEqualByComparingTo("2600.00");

        // Zero-amount memo legs trace all six cancelled ring invoices.
        java.util.Set<String> memoInvoices = new java.util.HashSet<>();
        for (JsonNode leg : cny.get("legs")) {
            if (leg.get("amount").asDouble() == 0) {
                for (JsonNode it : leg.get("items")) {
                    memoInvoices.add(it.get("invoiceNo").asText());
                }
            }
        }
        assertThat(memoInvoices).contains(
                "INV-A-1001", "INV-B-2001", "INV-C-3001",
                "INV-A-1002", "INV-B-2002", "INV-C-3002");

        // NA-NOFF: netting forbidden -> the closed ring survives as 3 original debts.
        JsonNode noff = group(sim, "NA-NOFF");
        assertThat(noff.get("passThrough").asBoolean()).isTrue();
        assertThat(noff.get("legs").size()).isEqualTo(3);
        for (JsonNode leg : noff.get("legs")) {
            assertThat(leg.get("isOriginal").asBoolean()).isTrue();
            assertThat(leg.get("amount").decimalValue()).isEqualByComparingTo("500.00");
        }

        // NA-XCCY: USD leg converted at the 08:30 snapshot (agreement fx_as_of 09:00),
        // +0.005 rounding diff recorded, EUR out-of-scope claim excluded.
        JsonNode xccy = group(sim, "NA-XCCY");
        assertThat(xccy.get("crossCurrency").asBoolean()).isTrue();
        assertThat(xccy.get("settlementCurrency").asText()).isEqualTo("CNY");
        assertThat(reasons(xccy)).contains("CCY_NOT_ALLOWED");
        // The USD claim is split across a real leg (receivable side at B) and the
        // set-off memo at E; inspect its debtor-side (PAYER) slice for full conversion.
        JsonNode usdItem = findItem(xccy, "CLM-XC-002", "PAYER");
        assertThat(usdItem.get("fxRate").asText()).startsWith("7.20535");
        assertThat(usdItem.get("fxAsOf").asText()).startsWith("2026-09-30T08:30:00");
        assertThat(usdItem.get("convertedExact").decimalValue()).isEqualByComparingTo("720.535000");
        assertThat(usdItem.get("convertedBooked").decimalValue()).isEqualByComparingTo("720.54");
        assertThat(usdItem.get("roundingDiff").decimalValue()).isEqualByComparingTo("0.005000");
        // Rounding does not move any entity off its cent balance: exactly 2 cash legs.
        assertThat(cashLegs(xccy)).isEqualTo(2);

        // Per-entity cent balance: signed cash flows equal the booked positions.
        assertLegsBalancePerEntity(xccy);
        assertLegsBalancePerEntity(cny);

        String batchId = sim.get("id").asText();
        assertThat(sim.get("status").asText()).isEqualTo("SIMULATED");

        // Simulation must not settle original claims.
        JsonNode claimsAfterSim = claims();
        assertThat(findClaim(claimsAfterSim, "CLM-R1-001").get("status").asText()).isEqualTo("OPEN");

        // Confirm -> claims get the batch reference; then simulate payment.
        JsonNode confirmed = postJson("/api/batches/" + batchId + "/confirm");
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        JsonNode claimsAfterConfirm = claims();
        assertThat(findClaim(claimsAfterConfirm, "CLM-R1-001").get("offsetBatchId").asText())
                .isEqualTo(batchId);
        assertThat(findClaim(claimsAfterConfirm, "CLM-R1-001").get("status").asText())
                .isEqualTo("SETTLED");
        // Pass-through group claims stay open: set-off was never allowed.
        assertThat(findClaim(claimsAfterConfirm, "CLM-NF-001").get("status").asText())
                .isEqualTo("OPEN");

        JsonNode paid = postJson("/api/batches/" + batchId + "/pay-simulated");
        assertThat(paid.get("status").asText()).isEqualTo("PAID_SIMULATED");
        boolean atLeastOneSimulatedPayment = false;
        for (JsonNode g : paid.get("groups")) {
            for (JsonNode leg : g.get("legs")) {
                if (leg.get("amount").asDouble() > 0) {
                    assertThat(leg.get("paidSimulatedAt").asText()).isNotBlank();
                    atLeastOneSimulatedPayment = true;
                }
            }
        }
        assertThat(atLeastOneSimulatedPayment).isTrue();

        // Cannot confirm twice.
        mockMvc.perform(post("/api/batches/" + batchId + "/confirm"))
                .andExpect(status().isConflict());
    }

    private JsonNode simulate(LocalDate date) throws Exception {
        var req = json.createObjectNode().put("valuationDate", date.toString());
        return json.readTree(mockMvc.perform(post("/api/batches/simulate")
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String path) throws Exception {
        return json.readTree(mockMvc.perform(post(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode claims() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/claims"))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode group(JsonNode batch, String agreementCode) {
        for (JsonNode g : batch.get("groups")) {
            if (g.get("agreementCode").asText().equals(agreementCode)) {
                return g;
            }
        }
        throw new AssertionError("group missing: " + agreementCode);
    }

    private long cashLegs(JsonNode g) {
        long n = 0;
        for (JsonNode leg : g.get("legs")) {
            if (leg.get("amount").asDouble() > 0) {
                n++;
            }
        }
        return n;
    }

    private java.util.List<String> reasons(JsonNode g) {
        java.util.List<String> out = new java.util.ArrayList<>();
        g.get("exclusions").forEach(e -> out.add(e.get("reasonCode").asText()));
        return out;
    }

    private JsonNode findItem(JsonNode g, String claimId, String side) {
        for (JsonNode leg : g.get("legs")) {
            for (JsonNode it : leg.get("items")) {
                if (it.get("claimId").asText().equals(claimId)
                        && (side == null || it.get("side").asText().equals(side))) {
                    return it;
                }
            }
        }
        throw new AssertionError("item missing: " + claimId + " side " + side);
    }

    private JsonNode findClaim(JsonNode arr, String id) {
        for (JsonNode c : arr) {
            if (c.get("id").asText().equals(id)) {
                return c;
            }
        }
        throw new AssertionError("claim missing: " + id);
    }

    private void assertLegsBalancePerEntity(JsonNode g) {
        java.util.Map<String, java.math.BigDecimal> flow = new java.util.HashMap<>();
        for (JsonNode leg : g.get("legs")) {
            if (leg.get("amount").asDouble() == 0) {
                continue;
            }
            java.math.BigDecimal amt = leg.get("amount").decimalValue();
            flow.merge(leg.get("payerCode").asText(), amt, java.math.BigDecimal::add);
            flow.merge(leg.get("receiverCode").asText(), amt.negate(), java.math.BigDecimal::add);
        }
        java.math.BigDecimal sum = flow.values().stream()
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("0.00");
    }
}
