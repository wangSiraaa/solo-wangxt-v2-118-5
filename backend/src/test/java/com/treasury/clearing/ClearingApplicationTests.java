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

    /**
     * Optional maturity screen on the NA-DUE demo group:
     * matured (due on/before valuation date) and no-due-date invoices enter netting,
     * the after-valuation-date invoice is deferred with NOT_DUE; once the valuation
     * date moves past its due date a new batch includes it while the old batch is
     * untouched; pass-through (NA-NOFF) agreements keep every original debt.
     */
    @Test
    void maturityScreenIsOptionalSavedAndReversibleByAdvancingValuationDate() throws Exception {
        // ---- screen OFF: existing trial behaviour, all three invoices net ----
        JsonNode plain = simulate(LocalDate.of(2026, 9, 30), false);
        assertThat(plain.get("onlyDueClaims").asBoolean()).isFalse();
        JsonNode duePlain = group(plain, "NA-DUE");
        assertThat(duePlain.get("exclusions").size()).isZero();
        // A->B 300 (due 09-30) nets B->A 200 (due 10-20), A->B 100 (no due date):
        // one real leg A -> B for 200, and the no-due invoice stays in.
        assertThat(duePlain.get("originalLegCount").asInt()).isEqualTo(3);
        assertThat(cashAmount(duePlain, "A", "B")).isEqualByComparingTo("200.00");

        // ---- screen ON at 2026-09-30: only the matured + no-due invoices net ----
        JsonNode screened = simulate(LocalDate.of(2026, 9, 30), true);
        assertThat(screened.get("onlyDueClaims").asBoolean()).isTrue();
        JsonNode due = group(screened, "NA-DUE");
        assertThat(due.get("originalLegCount").asInt()).isEqualTo(2);
        assertThat(due.get("exclusions").size()).isEqualTo(1);
        JsonNode deferred = due.get("exclusions").get(0);
        assertThat(deferred.get("reasonCode").asText()).isEqualTo("NOT_DUE");
        assertThat(deferred.get("claimId").asText()).isEqualTo("CLM-DUE-002");
        assertThat(deferred.get("reasonDetail").asText())
                .contains("2026-10-20")
                .containsIgnoringCase("after the valuation date");
        // B->A 200 is gone: the two A->B invoices (300 + 100) survive as real payments.
        assertThat(cashAmount(due, "A", "B")).isEqualByComparingTo("400.00");
        java.util.Set<String> includedInvoices = new java.util.HashSet<>();
        for (JsonNode leg : due.get("legs")) {
            for (JsonNode it : leg.get("items")) {
                includedInvoices.add(it.get("invoiceNo").asText());
            }
        }
        assertThat(includedInvoices).containsExactlyInAnyOrder("INV-DA-1001", "INV-DA-1002");

        // The choice is persisted: reloading the batch shows onlyDueClaims=true.
        JsonNode reloaded = json.readTree(mockMvc.perform(get("/api/batches/"
                        + screened.get("id").asText())).andReturn().getResponse().getContentAsString());
        assertThat(reloaded.get("onlyDueClaims").asBoolean()).isTrue();

        // Pledged/disputed claims keep their original rules under the screen even when
        // they are also not yet due (the NA-CNY ring invoices, due in October, are
        // reported separately as NOT_DUE — the maturity screen is working there too).
        JsonNode cny = group(screened, "NA-CNY");
        assertThat(reasonOf(cny, "CLM-EX-001")).isEqualTo("PLEDGED");
        assertThat(reasonOf(cny, "CLM-EX-002")).isEqualTo("DISPUTED");

        // Pass-through agreements ignore the screen: three original debts retained.
        JsonNode noff = group(screened, "NA-NOFF");
        assertThat(noff.get("passThrough").asBoolean()).isTrue();
        assertThat(noff.get("legs").size()).isEqualTo(3);
        assertThat(reasons(noff)).doesNotContain("NOT_DUE");

        // ---- valuation date advanced past 2026-10-20: deferred invoice now nets ----
        JsonNode advanced = simulate(LocalDate.of(2026, 10, 31), true);
        JsonNode dueAdvanced = group(advanced, "NA-DUE");
        assertThat(dueAdvanced.get("exclusions").size()).isZero();
        assertThat(dueAdvanced.get("originalLegCount").asInt()).isEqualTo(3);
        assertThat(cashAmount(dueAdvanced, "A", "B")).isEqualByComparingTo("200.00");

        // ---- old batches are immutable snapshots ----
        JsonNode oldAgain = json.readTree(mockMvc.perform(get("/api/batches/"
                        + screened.get("id").asText())).andReturn().getResponse().getContentAsString());
        assertThat(oldAgain.get("valuationDate").asText()).isEqualTo("2026-09-30");
        JsonNode oldDue = group(oldAgain, "NA-DUE");
        assertThat(oldDue.get("exclusions").size()).isEqualTo(1);
        assertThat(oldDue.get("exclusions").get(0).get("claimId").asText())
                .isEqualTo("CLM-DUE-002");
    }

    private JsonNode simulate(LocalDate date) throws Exception {
        return simulate(date, false);
    }

    private JsonNode simulate(LocalDate date, boolean onlyDueClaims) throws Exception {
        var req = json.createObjectNode()
                .put("valuationDate", date.toString())
                .put("onlyDueClaims", onlyDueClaims);
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

    private java.math.BigDecimal cashAmount(JsonNode g, String payer, String receiver) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (JsonNode leg : g.get("legs")) {
            if (leg.get("amount").asDouble() > 0
                    && leg.get("payerCode").asText().equals(payer)
                    && leg.get("receiverCode").asText().equals(receiver)) {
                total = total.add(leg.get("amount").decimalValue());
            }
        }
        return total;
    }

    private java.util.List<String> reasons(JsonNode g) {
        java.util.List<String> out = new java.util.ArrayList<>();
        g.get("exclusions").forEach(e -> out.add(e.get("reasonCode").asText()));
        return out;
    }

    private String reasonOf(JsonNode g, String claimId) {
        for (JsonNode e : g.get("exclusions")) {
            if (e.get("claimId").asText().equals(claimId)) {
                return e.get("reasonCode").asText();
            }
        }
        throw new AssertionError("exclusion missing for claim: " + claimId);
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
