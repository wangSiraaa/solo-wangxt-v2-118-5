package com.treasury.clearing.service.plan;

import com.treasury.clearing.domain.ClaimStatus;
import com.treasury.clearing.domain.LedgerSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class NettingPlannerTest {

    private static final LocalDate VAL = LocalDate.of(2026, 9, 30);
    private static final OffsetDateTime FX_AT = OffsetDateTime.parse("2026-09-30T09:00:00Z");

    private static PlannerClaim claim(String id, String debtor, String creditor,
                                      String amount, String ccy, ClaimStatus status) {
        // No due date by default: keeps the historical eligibility rule.
        return new PlannerClaim(id, "INV-" + id, "AGR", debtor, creditor,
                new BigDecimal(amount), ccy, status, null, "test");
    }

    private static PlannerClaim claim(String id, String debtor, String creditor, String amount) {
        return claim(id, debtor, creditor, amount, "CNY", ClaimStatus.OPEN);
    }

    private static PlannerClaim dueClaim(String id, String debtor, String creditor, String amount,
                                         LocalDate dueDate) {
        return new PlannerClaim(id, "INV-" + id, "AGR", debtor, creditor,
                new BigDecimal(amount), "CNY", ClaimStatus.OPEN, dueDate, "test");
    }

    private static AgreementSpec spec(boolean allowsNetting, boolean crossCcy, String... members) {
        return new AgreementSpec("AGR", "test agreement", allowsNetting, crossCcy,
                new HashSet<>(Arrays.asList(members)), Set.of(), "CNY", "A");
    }

    private static FxRateProvider fixedFx(Map<String, String> rates) {
        return (from, to, asOf) -> {
            if (from.equals(to)) {
                return Optional.of(new FxQuote(BigDecimal.ONE, asOf, "TEST", false, null));
            }
            String r = rates.get(from + "->" + to);
            if (r != null) {
                return Optional.of(new FxQuote(new BigDecimal(r),
                        OffsetDateTime.parse("2026-09-30T08:30:00Z"), "TEST", false, null));
            }
            String reverse = rates.get(to + "->" + from);
            if (reverse != null) {
                return Optional.of(new FxQuote(
                        BigDecimal.ONE.divide(new BigDecimal(reverse), 10, java.math.RoundingMode.HALF_UP),
                        OffsetDateTime.parse("2026-09-30T08:30:00Z"), "TEST(inverse)", true, null));
            }
            return Optional.empty();
        };
    }

    private BigDecimal cashAmount(PlannedGroup g, String payer, String receiver) {
        return g.legs().stream()
                .filter(l -> l.amount().signum() > 0
                        && l.payerCode().equals(payer) && l.receiverCode().equals(receiver))
                .map(PlannedLeg::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int cashLegCount(PlannedGroup g) {
        return (int) g.legs().stream().filter(l -> l.amount().signum() > 0).count();
    }

    // ------------------------------------------------------------------
    // Acceptance 1: three-party ring collapses from 3 debts to 2 payments
    // ------------------------------------------------------------------
    @Test
    void unbalancedThreePartyRingShrinksToTwoLegs() {
        PlannedGroup g = NettingPlanner.plan(
                spec(true, false, "A", "B", "C"),
                List.of(
                        claim("c1", "A", "B", "1000.00"),
                        claim("c2", "B", "C", "600.00"),
                        claim("c3", "C", "A", "400.00")),
                "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), OffsetDateTime.parse("2026-09-30T09:00:00Z"), null);

        assertThat(g.exclusions()).isEmpty();
        assertThat(cashLegCount(g)).isEqualTo(2);
        // Net positions: A +600 (pays), B -400, C -200 -> A pays B 400 and C 200.
        assertThat(cashAmount(g, "A", "B")).isEqualByComparingTo("400.00");
        assertThat(cashAmount(g, "A", "C")).isEqualByComparingTo("200.00");
        assertThat(g.netAmount()).isEqualByComparingTo("600.00");
        assertThat(g.grossAmount()).isEqualByComparingTo("2000.00");
        // Every original invoice is traceable through memo + real legs (payer side once).
        for (String cid : List.of("c1", "c2", "c3")) {
            assertThat(g.legs().stream().flatMap(l -> l.items().stream())
                    .filter(i -> i.claimId().equals(cid) && i.side() == LedgerSide.PAYER)
                    .map(i -> i.convertedBooked().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(new BigDecimal(
                            cid.equals("c1") ? "1000.00" : cid.equals("c2") ? "600.00" : "400.00"));
        }
    }

    @Test
    void fullyClosedRingProducesZeroCashLegsButKeepsMemoTrace() {
        PlannedGroup g = NettingPlanner.plan(
                spec(true, false, "A", "B", "C"),
                List.of(
                        claim("c1", "A", "B", "500.00"),
                        claim("c2", "B", "C", "500.00"),
                        claim("c3", "C", "A", "500.00")),
                "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), OffsetDateTime.parse("2026-09-30T09:00:00Z"), null);

        assertThat(cashLegCount(g)).isZero();
        assertThat(g.netAmount()).isEqualByComparingTo("0.00");
        // Zero-amount memo legs carry the three cancelled invoices.
        Set<String> traced = new HashSet<>();
        g.legs().forEach(l -> {
            assertThat(l.amount()).isEqualByComparingTo("0.00");
            l.items().forEach(i -> {
                assertThat(i.side()).isNotNull();
                traced.add(i.claimId());
            });
        });
        assertThat(traced).containsExactlyInAnyOrder("c1", "c2", "c3");
    }

    // ------------------------------------------------------------------
    // Acceptance 2: netting forbidden -> debts preserved exactly
    // ------------------------------------------------------------------
    @Test
    void passThroughPreservesEveryOriginalDebt() {
        PlannedGroup g = NettingPlanner.plan(
                spec(false, false, "A", "B", "C"),
                List.of(
                        claim("c1", "A", "B", "500.00"),
                        claim("c2", "B", "C", "500.00"),
                        claim("c3", "C", "A", "500.00")),
                "CNY", GroupMode.PASS_THROUGH,
                fixedFx(Map.of()), OffsetDateTime.parse("2026-09-30T09:00:00Z"), null);

        assertThat(g.passThrough()).isTrue();
        assertThat(g.legs()).hasSize(3);
        assertThat(g.legs()).allSatisfy(l -> {
            assertThat(l.original()).isTrue();
            assertThat(l.amount()).isEqualByComparingTo("500.00");
            assertThat(l.items()).hasSize(1);
            assertThat(l.items().get(0).fxRate()).isEqualByComparingTo("1");
        });
        assertThat(g.legs()).extracting(PlannedLeg::payerCode)
                .containsExactly("A", "B", "C");
    }

    // ------------------------------------------------------------------
    // Pledged and disputed claims are excluded, others still net.
    // ------------------------------------------------------------------
    @Test
    void pledgedAndDisputedClaimsAreExcluded() {
        PlannedGroup g = NettingPlanner.plan(
                spec(true, false, "A", "B", "C"),
                List.of(
                        claim("c1", "A", "B", "100.00"),
                        claim("c2", "B", "A", "100.00", "CNY", ClaimStatus.PLEDGED),
                        claim("c3", "B", "A", "100.00", "CNY", ClaimStatus.DISPUTED)),
                "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), OffsetDateTime.parse("2026-09-30T09:00:00Z"), null);

        assertThat(g.originalLegCount()).isEqualTo(1);
        assertThat(g.exclusions()).extracting(PlannedExclusion::reasonCode)
                .containsExactlyInAnyOrder("PLEDGED", "DISPUTED");
        // Only A->B survives and genuinely must be paid (nothing to offset against).
        assertThat(cashLegCount(g)).isEqualTo(1);
        assertThat(cashAmount(g, "A", "B")).isEqualByComparingTo("100.00");
    }

    @Test
    void nonMembersAndForeignCurrencyAreExcluded() {        PlannedGroup g = NettingPlanner.plan(
                spec(true, false, "A", "B"),
                List.of(
                        claim("c1", "A", "B", "100.00"),
                        claim("c2", "B", "X", "100.00"),
                        claim("c3", "A", "B", "100.00", "USD", ClaimStatus.OPEN)),
                "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), OffsetDateTime.parse("2026-09-30T09:00:00Z"), null);

        assertThat(g.exclusions()).extracting(PlannedExclusion::reasonCode)
                .containsExactlyInAnyOrder("NOT_MEMBER_PAIR", "CROSS_CCY_NOT_ALLOWED");
    }

    // ------------------------------------------------------------------
    // Optional maturity gate ("only claims due at valuation date")
    // ------------------------------------------------------------------
    @Test
    void dueFilterOnIncludesOnlyTheMaturedInvoice() {
        // One matured and one not-yet-due invoice in the same netting pocket.
        PlannedGroup g = NettingPlanner.plan(
                spec(true, false, "A", "B"),
                List.of(
                        dueClaim("due", "A", "B", "100.00", VAL),
                        dueClaim("future", "B", "A", "100.00", VAL.plusDays(10))),
                "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), FX_AT, null, VAL, true);

        assertThat(g.originalLegCount()).isEqualTo(1);
        assertThat(g.exclusions()).extracting(PlannedExclusion::reasonCode)
                .containsExactly("NOT_DUE_AT_VALUATION");
        PlannedExclusion ex = g.exclusions().get(0);
        assertThat(ex.claimId()).isEqualTo("future");
        assertThat(ex.reasonDetail()).contains(VAL.plusDays(10).toString()).contains(VAL.toString());
        // The matured invoice cannot be offset against anything and survives as a real leg.
        assertThat(cashLegCount(g)).isEqualTo(1);
        assertThat(cashAmount(g, "A", "B")).isEqualByComparingTo("100.00");
    }

    @Test
    void dueFilterOffKeepsTheHistoricalBehaviour() {
        PlannedGroup g = NettingPlanner.plan(
                spec(true, false, "A", "B"),
                List.of(
                        dueClaim("due", "A", "B", "100.00", VAL),
                        dueClaim("future", "B", "A", "100.00", VAL.plusDays(10))),
                "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), FX_AT, null, VAL, false);

        // Without the gate the two opposite claims net to zero: 2 included, 0 excluded.
        assertThat(g.originalLegCount()).isEqualTo(2);
        assertThat(g.exclusions()).isEmpty();
        assertThat(cashLegCount(g)).isZero();
    }

    @Test
    void dueFilterOnTreatsDueOnValuationDateAsMatured() {
        PlannedGroup g = NettingPlanner.plan(
                spec(true, false, "A", "B"),
                List.of(
                        dueClaim("on", "A", "B", "100.00", VAL),
                        dueClaim("before", "B", "A", "100.00", VAL.minusDays(1))),
                "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), FX_AT, null, VAL, true);

        assertThat(g.exclusions()).isEmpty();
        assertThat(g.originalLegCount()).isEqualTo(2);
        assertThat(cashLegCount(g)).isZero();
    }

    @Test
    void dueFilterOnLeavesNullDueDateClaimsOnTheExistingRule() {
        // A claim without a due date stays eligible even with the gate enabled.
        PlannedGroup g = NettingPlanner.plan(
                spec(true, false, "A", "B"),
                List.of(
                        claim("nodue", "A", "B", "100.00"),
                        dueClaim("future", "B", "A", "100.00", VAL.plusDays(10))),
                "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), FX_AT, null, VAL, true);

        assertThat(g.exclusions()).extracting(PlannedExclusion::reasonCode)
                .containsExactly("NOT_DUE_AT_VALUATION");
        assertThat(g.originalLegCount()).isEqualTo(1);
        assertThat(g.legs().stream().flatMap(l -> l.items().stream())
                .map(PlannedItem::claimId).findFirst().orElseThrow()).isEqualTo("nodue");
    }

    @Test
    void dueFilterOnStillExcludesPledgedAndDisputedFirst() {
        PlannedGroup g = NettingPlanner.plan(
                spec(true, false, "A", "B"),
                List.of(
                        dueClaim("future-pledge", "A", "B", "100.00", VAL.plusDays(10)),
                        new PlannerClaim("pledge", "INV-pledge", "AGR", "A", "B",
                                new BigDecimal("100.00"), "CNY", ClaimStatus.PLEDGED,
                                VAL, "test"),
                        new PlannerClaim("dispute", "INV-dispute", "AGR", "A", "B",
                                new BigDecimal("100.00"), "CNY", ClaimStatus.DISPUTED,
                                VAL, "test")),
                "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), FX_AT, null, VAL, true);

        assertThat(g.exclusions()).extracting(PlannedExclusion::reasonCode)
                .containsExactlyInAnyOrder("NOT_DUE_AT_VALUATION", "PLEDGED", "DISPUTED");
    }

    @Test
    void dueFilterIsIgnoredByPassThroughAgreements() {
        // Set-off forbidden -> every original debt survives 1:1, even if not due yet.
        PlannedGroup g = NettingPlanner.plan(
                spec(false, false, "A", "B"),
                List.of(dueClaim("future", "A", "B", "500.00", VAL.plusDays(10))),
                "CNY", GroupMode.PASS_THROUGH,
                fixedFx(Map.of()), FX_AT, null, VAL, true);

        assertThat(g.passThrough()).isTrue();
        assertThat(g.legs()).hasSize(1);
        assertThat(g.legs().get(0).original()).isTrue();
        assertThat(g.legs().get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(g.exclusions()).isEmpty();
    }

    @Test
    void advancingValuationDateAdmitsThePreviouslyDeferredClaim() {
        List<PlannerClaim> claims = List.of(
                dueClaim("due", "A", "B", "100.00", VAL),
                dueClaim("future", "B", "A", "100.00", VAL.plusDays(10)));

        PlannedGroup atValuation = NettingPlanner.plan(
                spec(true, false, "A", "B"), claims, "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), FX_AT, null, VAL, true);
        assertThat(atValuation.originalLegCount()).isEqualTo(1);
        assertThat(atValuation.exclusions()).extracting(PlannedExclusion::reasonCode)
                .containsExactly("NOT_DUE_AT_VALUATION");

        // Re-trial after the valuation date passes the due date -> both claims enter,
        // they offset to zero cash. (The old batch is stored and is not recomputed.)
        PlannedGroup later = NettingPlanner.plan(
                spec(true, false, "A", "B"), claims, "CNY", GroupMode.NET_SAME_CURRENCY,
                fixedFx(Map.of()), FX_AT, null, VAL.plusDays(20), true);
        assertThat(later.originalLegCount()).isEqualTo(2);
        assertThat(later.exclusions()).isEmpty();
        assertThat(cashLegCount(later)).isZero();
    }

    // ------------------------------------------------------------------
    // Acceptance 3: FX conversion lists rate/time/diff and cents still balance
    // ------------------------------------------------------------------
    @Test
    void crossCurrencyConversionIsAuditableAndBalancedAtCentLevel() {
        // 100 USD * 7.20535 = 720.535 exact -> 720.54 booked, +0.005 rounding diff.
        PlannedGroup g = NettingPlanner.plan(
                spec(true, true, "A", "B", "E"),
                List.of(
                        claim("u1", "E", "B", "100.00", "USD", ClaimStatus.OPEN),
                        claim("c1", "B", "E", "720.54", "CNY", ClaimStatus.OPEN)),
                "CNY", GroupMode.NET_CROSS_CURRENCY,
                fixedFx(Map.of("USD->CNY", "7.20535")),
                OffsetDateTime.parse("2026-09-30T09:00:00Z"), null);

        assertThat(g.exclusions()).isEmpty();
        // Both parties net to exactly zero at cent level -> zero cash legs.
        assertThat(cashLegCount(g)).isZero();
        PlannedItem usdItem = g.legs().stream()
                .flatMap(l -> l.items().stream())
                .filter(i -> i.originalCurrency().equals("USD"))
                .findFirst().orElseThrow();
        assertThat(usdItem.fxRate()).isEqualByComparingTo("7.20535");
        assertThat(usdItem.fxAsOf()).isEqualTo(OffsetDateTime.parse("2026-09-30T08:30:00Z"));
        assertThat(usdItem.fxSource()).isEqualTo("TEST");
        assertThat(usdItem.convertedExact().abs()).isEqualByComparingTo("720.535000");
        assertThat(usdItem.convertedBooked().abs()).isEqualByComparingTo("720.54");
        assertThat(usdItem.roundingDiff().abs()).isEqualByComparingTo("0.005000");
        assertThat(usdItem.roundingBearerCode()).isEqualTo("A");
    }

    @Test
    void crossCurrencyMissingRateExcludesOnlyThatClaim() {
        PlannedGroup g = NettingPlanner.plan(
                spec(true, true, "A", "B", "E"),
                List.of(
                        claim("u1", "E", "B", "100.00", "EUR", ClaimStatus.OPEN),
                        claim("c1", "A", "B", "100.00", "CNY", ClaimStatus.OPEN)),
                "CNY", GroupMode.NET_CROSS_CURRENCY,
                fixedFx(Map.of()),
                OffsetDateTime.parse("2026-09-30T09:00:00Z"), null);

        assertThat(g.exclusions()).extracting(PlannedExclusion::reasonCode)
                .containsExactly("FX_RATE_MISSING");
        assertThat(g.originalLegCount()).isEqualTo(1);
        assertThat(cashAmount(g, "A", "B")).isEqualByComparingTo("100.00");
    }

    @Test
    void inverseRateIsUsedWhenOnlyReversePairExists() {
        PlannedGroup g = NettingPlanner.plan(
                spec(true, true, "A", "E"),
                List.of(claim("u1", "E", "A", "100.00", "USD", ClaimStatus.OPEN)),
                "CNY", GroupMode.NET_CROSS_CURRENCY,
                fixedFx(Map.of("CNY->USD", "0.138785")),
                OffsetDateTime.parse("2026-09-30T09:00:00Z"), null);

        PlannedItem item = g.legs().get(0).items().stream()
                .filter(i -> i.originalCurrency().equals("USD")).findFirst().orElseThrow();
        assertThat(item.fxInverted()).isTrue();
        assertThat(item.fxSource()).contains("inverse");
    }

    // ------------------------------------------------------------------
    // Balance invariants under random ledgers: entity legs reproduce booked
    // net positions exactly, every invoice is traced once, positions sum zero.
    // ------------------------------------------------------------------
    @Test
    void randomizedLedgersAlwaysBalanceAtCentLevel() {
        Random rnd = new Random(42);
        String[] entities = {"A", "B", "C", "D"};
        Map<String, String> fx = Map.of("USD->CNY", "7.20535");
        for (int run = 0; run < 200; run++) {
            int n = 1 + rnd.nextInt(8);
            List<PlannerClaim> claims = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String d = entities[rnd.nextInt(entities.length)];
                String c = entities[rnd.nextInt(entities.length)];
                if (d.equals(c)) {
                    i--;
                    continue;
                }
                String ccy = rnd.nextInt(3) == 0 ? "USD" : "CNY";
                String amount = (1 + rnd.nextInt(9999)) + "." + String.format("%02d", rnd.nextInt(100));
                ClaimStatus st = rnd.nextInt(15) == 0 ? ClaimStatus.PLEDGED : ClaimStatus.OPEN;
                claims.add(claim("k" + i, d, c, amount, ccy, st));
            }
            PlannedGroup g = NettingPlanner.plan(spec(true, true, entities),
                    claims, "CNY", GroupMode.NET_CROSS_CURRENCY, fixedFx(fx),
                    OffsetDateTime.parse("2026-09-30T09:00:00Z"), null);

            Map<String, BigDecimal> fromLegs = new HashMap<>();
            for (PlannedLeg l : g.legs()) {
                if (l.amount().signum() == 0) {
                    continue;
                }
                fromLegs.merge(l.payerCode(), l.amount(), BigDecimal::add);
                fromLegs.merge(l.receiverCode(), l.amount().negate(), BigDecimal::add);
            }
            for (Map.Entry<String, BigDecimal> e : g.bookedPositions().entrySet()) {
                assertThat(fromLegs.getOrDefault(e.getKey(), BigDecimal.ZERO.setScale(2)))
                        .as("run %d entity %s", run, e.getKey())
                        .isEqualByComparingTo(e.getValue());
            }
            // Zero-sum across entities.
            BigDecimal sum = fromLegs.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo("0.00");
            // Included claims traced exactly once on each side.
            Set<String> includedIds = new HashSet<>();
            claims.stream().filter(x -> x.status() == ClaimStatus.OPEN).forEach(x -> includedIds.add(x.id()));
            g.exclusions().forEach(x -> includedIds.remove(x.claimId()));
            for (String id : includedIds) {
                for (LedgerSide side : LedgerSide.values()) {
                    BigDecimal traced = g.legs().stream().flatMap(l -> l.items().stream())
                            .filter(i -> i.claimId().equals(id) && i.side() == side)
                            .map(i -> i.convertedBooked().abs())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    assertThat(traced.signum()).as("run %d claim %s side %s", run, id, side).isGreaterThan(0);
                }
            }
        }
    }

    @Test
    void missingAgreementRateOnCrossCcyDoesNotLeakAsZero() {
        // A missing FX rate excludes the claim rather than converting it to zero.
        PlannedGroup g = NettingPlanner.plan(spec(true, true, "A", "B"),
                List.of(claim("u1", "A", "B", "10.00", "JPY", ClaimStatus.OPEN)),
                "CNY", GroupMode.NET_CROSS_CURRENCY, fixedFx(Map.of()),
                OffsetDateTime.parse("2026-09-30T09:00:00Z"), null);
        assertThat(g.originalLegCount()).isZero();
        assertThat(g.exclusions()).extracting(PlannedExclusion::reasonCode)
                .containsExactly("FX_RATE_MISSING");
    }
}
