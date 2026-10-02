package com.treasury.clearing.service.plan;

import com.treasury.clearing.domain.ClaimStatus;
import com.treasury.clearing.domain.LedgerSide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java multilateral netting engine. All monetary math is {@link BigDecimal},
 * HALF_UP; no double is used anywhere.
 *
 * <h3>Model</h3>
 * Each included claim is converted to the pocket currency (rate 1 for same currency,
 * 6dp exact, 2dp booked) and contributes a <b>payable slice</b> at the debtor and a
 * <b>receivable slice</b> at the creditor. Per entity:
 * <pre>
 *   position = bookedPayable - bookedReceivable
 * </pre>
 * Slices are then split into two pools:
 * <ul>
 *   <li><b>real pool</b> — net payers fund {@code position} from their payable slices;
 *       net receivers receive {@code -position} into their receivable slices. Greedy
 *       matching yields the actual (cent-balanced) payment legs;</li>
 *   <li><b>offset pool</b> — everything else, i.e. the volumes cancelled purely by
 *       set-off. Global matching of payable vs receivable slices (never pairing an
 *       entity with itself or the two sides of the same claim) yields zero-amount
 *       "memo" legs that keep full invoice traceability. A closed ring therefore
 *       reduces to zero payment legs while every cancelled invoice stays visible;</li>
 *   <li>offset slices that can only meet their own twin represent a genuinely
 *       surviving debt and are emitted as original legs instead of fake memos.</li>
 * </ul>
 *
 * Because booked slices sum to the cent-rounded positions, the real legs reproduce
 * every legal entity's balance exactly — small FX rounding differences cannot change
 * any entity's net position.
 *
 * <p>No bank interface exists here or anywhere else: output is a trial plan only.
 */
public final class NettingPlanner {

    public static final int MONEY_SCALE = 2;
    public static final int EXACT_SCALE = 6;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private NettingPlanner() {
    }

    /** A payable-side or receivable-side slice of one converted claim. */
    private static final class Slice {
        final PlannerClaim claim;
        final LedgerSide side; // PAYER: slice sits at debtor; RECEIVER: at creditor
        final BigDecimal rate;
        final OffsetDateTime fxAsOf;
        final String fxSource;
        final boolean inverted;
        BigDecimal bookedRemaining;
        BigDecimal exactRemaining;

        Slice(PlannerClaim claim, LedgerSide side, BigDecimal rate, OffsetDateTime fxAsOf,
              String fxSource, boolean inverted, BigDecimal booked, BigDecimal exact) {
            this.claim = claim;
            this.side = side;
            this.rate = rate;
            this.fxAsOf = fxAsOf;
            this.fxSource = fxSource;
            this.inverted = inverted;
            this.bookedRemaining = booked;
            this.exactRemaining = exact;
        }

        String entityCode() {
            return side == LedgerSide.PAYER ? claim.debtorCode() : claim.creditorCode();
        }
    }

    public static PlannedGroup plan(AgreementSpec spec,
                                    List<PlannerClaim> claims,
                                    String groupCurrency,
                                    GroupMode mode,
                                    FxRateProvider fxProvider,
                                    OffsetDateTime fxAsOf,
                                    BigDecimal fxMargin) {
        boolean passThrough = mode == GroupMode.PASS_THROUGH;

        List<PlannedExclusion> exclusions = new ArrayList<>();
        List<Included> included = new ArrayList<>();
        Map<String, BigDecimal> exactPositions = new LinkedHashMap<>();
        Map<String, BigDecimal> bookedPositions = new LinkedHashMap<>();
        BigDecimal roundingDiffTotal = BigDecimal.ZERO.setScale(EXACT_SCALE, RM);
        BigDecimal gross = BigDecimal.ZERO.setScale(MONEY_SCALE, RM);
        int originalLegCount = 0;

        List<PlannerClaim> ordered = claims.stream()
                .sorted(Comparator.comparing(PlannerClaim::id))
                .toList();

        for (PlannerClaim c : ordered) {
            String reason = exclusionReason(spec, c, groupCurrency, mode);
            if (reason != null) {
                exclusions.add(new PlannedExclusion(c.id(), c.invoiceNo(), reason,
                        exclusionDetail(spec, c, groupCurrency, mode, reason)));
                continue;
            }
            if (passThrough) {
                // Set-off forbidden: the untouched original debt, original currency.
                gross = gross.add(c.amount());
                originalLegCount++;
                continue;
            }

            BigDecimal rate = BigDecimal.ONE.setScale(10, RM);
            OffsetDateTime rateAsOf = null;
            String rateSource = null;
            boolean inverted = false;
            if (!c.currency().equals(groupCurrency)) {
                FxQuote quote = fxProvider.quote(c.currency(), groupCurrency, fxAsOf).orElse(null);
                if (quote == null) {
                    exclusions.add(new PlannedExclusion(c.id(), c.invoiceNo(), "FX_RATE_MISSING",
                            c.currency() + "->" + groupCurrency + " at " + fxAsOf));
                    continue;
                }
                rate = quote.rate();
                if (fxMargin != null && fxMargin.signum() != 0) {
                    rate = rate.multiply(BigDecimal.ONE.add(fxMargin)).setScale(10, RM);
                }
                rateAsOf = quote.asOf();
                rateSource = quote.source();
                inverted = quote.inverted();
            }

            BigDecimal convertedExact = c.amount().multiply(rate).setScale(EXACT_SCALE, RM);
            BigDecimal convertedBooked = convertedExact.setScale(MONEY_SCALE, RM);
            roundingDiffTotal = roundingDiffTotal
                    .add(convertedBooked.subtract(convertedExact).setScale(EXACT_SCALE, RM));

            included.add(new Included(c, rate, rateAsOf, rateSource, inverted,
                    convertedBooked, convertedExact));
            exactPositions.merge(c.debtorCode(), convertedExact, BigDecimal::add);
            exactPositions.merge(c.creditorCode(), convertedExact.negate(), BigDecimal::add);
            bookedPositions.merge(c.debtorCode(), convertedBooked, BigDecimal::add);
            bookedPositions.merge(c.creditorCode(), convertedBooked.negate(), BigDecimal::add);
            gross = gross.add(convertedBooked);
            originalLegCount++;
        }

        if (passThrough) {
            return passThroughGroup(spec, groupCurrency, ordered, exclusions, gross, originalLegCount);
        }

        // ---- slice pools ------------------------------------------------------
        // Real legs fund net positions; everything else is internal set-off AT an entity
        // (its payable slices meeting its own receivable slices). The two sets are kept
        // per entity because set-off is strictly a within-entity operation.
        List<Slice> realPay = new ArrayList<>();
        List<Slice> realRecv = new ArrayList<>();
        Map<String, List<Slice>> offsetPayByEntity = new LinkedHashMap<>();
        Map<String, List<Slice>> offsetRecvByEntity = new LinkedHashMap<>();

        for (String entity : sortedEntities(bookedPositions)) {
            BigDecimal position = bookedPositions.get(entity).setScale(MONEY_SCALE, RM);
            List<Slice> paySlices = new ArrayList<>();
            List<Slice> recvSlices = new ArrayList<>();
            for (Included pc : included) {
                if (pc.claim.debtorCode().equals(entity)) {
                    paySlices.add(new Slice(pc.claim, LedgerSide.PAYER, pc.rate, pc.fxAsOf,
                            pc.fxSource, pc.inverted, pc.booked, pc.exact));
                }
                if (pc.claim.creditorCode().equals(entity)) {
                    recvSlices.add(new Slice(pc.claim, LedgerSide.RECEIVER, pc.rate, pc.fxAsOf,
                            pc.fxSource, pc.inverted, pc.booked, pc.exact));
                }
            }
            if (position.signum() > 0) {
                // Net payer: exactly 'position' cents stay real; the rest of its payable
                // volume is set off against all of its receivables.
                splitReal(paySlices, position, realPay,
                        offsetPayByEntity.computeIfAbsent(entity, k -> new ArrayList<>()));
                offsetRecvByEntity.computeIfAbsent(entity, k -> new ArrayList<>()).addAll(recvSlices);
            } else if (position.signum() < 0) {
                splitReal(recvSlices, position.negate(), realRecv,
                        offsetRecvByEntity.computeIfAbsent(entity, k -> new ArrayList<>()));
                offsetPayByEntity.computeIfAbsent(entity, k -> new ArrayList<>()).addAll(paySlices);
            } else {
                offsetPayByEntity.computeIfAbsent(entity, k -> new ArrayList<>()).addAll(paySlices);
                offsetRecvByEntity.computeIfAbsent(entity, k -> new ArrayList<>()).addAll(recvSlices);
            }
        }

        List<PlannedLeg> legs = new ArrayList<>();
        int[] seq = {0};

        // 1) Real payment legs: net payer -> net receiver, cent-balanced.
        BigDecimal netAmount = BigDecimal.ZERO.setScale(MONEY_SCALE, RM);
        while (true) {
            Slice pay = firstRemaining(realPay);
            Slice recv = firstRemaining(realRecv);
            if (pay == null || recv == null) {
                break;
            }
            BigDecimal cents = pay.bookedRemaining.min(recv.bookedRemaining);
            PlannedItem payerItem = take(pay, cents, spec.roundingBearerCode());
            PlannedItem receiverItem = take(recv, cents, spec.roundingBearerCode());
            legs.add(new PlannedLeg(pay.entityCode(), recv.entityCode(),
                    cents.setScale(MONEY_SCALE, RM),
                    payerItem.convertedExact().abs().setScale(EXACT_SCALE, RM),
                    BigDecimal.ZERO.setScale(MONEY_SCALE, RM), false, ++seq[0],
                    new ArrayList<>(List.of(payerItem, receiverItem))));
            netAmount = netAmount.add(cents);
        }
        if (firstRemaining(realPay) != null || firstRemaining(realRecv) != null) {
            throw new IllegalStateException("real pools not exhausted");
        }

        // 2) Internal set-off memo legs, per legal entity.
        // At entity E, its payable (E owes X) meets its receivable (Y owes E) of equal
        // booked amount; the cancelled chain runs Y -> E -> X with zero cash. Because
        // debtor != creditor on every claim, a payable slice and a receivable slice at
        // the same entity can never be two sides of the same claim, and the two queues
        // at an entity always have equal totals, so simple greedy matching is exact.
        for (String entity : sortedEntities(bookedPositions)) {
            List<Slice> pays = offsetPayByEntity.getOrDefault(entity, List.of());
            List<Slice> recvs = offsetRecvByEntity.getOrDefault(entity, List.of());
            while (true) {
                Slice pay = firstRemaining(pays);
                Slice recv = firstRemaining(recvs);
                if (pay == null || recv == null) {
                    break;
                }
                BigDecimal cents = pay.bookedRemaining.min(recv.bookedRemaining);
                PlannedItem payerItem = take(pay, cents, spec.roundingBearerCode());
                PlannedItem receiverItem = take(recv, cents, spec.roundingBearerCode());
                // Zero-amount cancelled chain: recv-claim debtor -> entity -> pay-claim creditor.
                legs.add(new PlannedLeg(
                        recv.claim.debtorCode(), pay.claim.creditorCode(),
                        BigDecimal.ZERO.setScale(MONEY_SCALE, RM),
                        BigDecimal.ZERO.setScale(EXACT_SCALE, RM),
                        BigDecimal.ZERO.setScale(MONEY_SCALE, RM), false, ++seq[0],
                        new ArrayList<>(List.of(payerItem, receiverItem))));
            }
            if (firstRemaining(pays) != null || firstRemaining(recvs) != null) {
                throw new IllegalStateException("offset queues not exhausted at entity " + entity);
            }
        }

        // ---- invariants: rounding must not break any legal entity's balance ----
        Map<String, BigDecimal> legFlow = new LinkedHashMap<>();
        for (PlannedLeg l : legs) {
            if (l.amount().signum() == 0) {
                continue;
            }
            legFlow.merge(l.payerCode(), l.amount(), BigDecimal::add);
            legFlow.merge(l.receiverCode(), l.amount().negate(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : bookedPositions.entrySet()) {
            BigDecimal fromLegs = legFlow.getOrDefault(e.getKey(), BigDecimal.ZERO.setScale(MONEY_SCALE, RM));
            if (fromLegs.compareTo(e.getValue()) != 0) {
                throw new IllegalStateException(
                        "entity balance broken for " + e.getKey()
                                + ": legs=" + fromLegs + " booked=" + e.getValue());
            }
        }
        BigDecimal exactZero = exactPositions.values().stream()
                .reduce(BigDecimal.ZERO.setScale(EXACT_SCALE, RM), BigDecimal::add)
                .setScale(EXACT_SCALE, RM);
        if (exactZero.signum() != 0) {
            throw new IllegalStateException("exact positions do not net to zero: " + exactZero);
        }
        // Every claim side is partitioned exactly once across all legs (real + memo +
        // restored original), so the graph trace from any leg covers each invoice fully.
        for (Included pc : included) {
            BigDecimal payBooked = BigDecimal.ZERO.setScale(MONEY_SCALE, RM);
            BigDecimal recvBooked = BigDecimal.ZERO.setScale(MONEY_SCALE, RM);
            for (PlannedLeg l : legs) {
                for (PlannedItem it : l.items()) {
                    if (!it.claimId().equals(pc.claim.id())) {
                        continue;
                    }
                    if (it.side() == LedgerSide.PAYER) {
                        payBooked = payBooked.add(it.convertedBooked().abs());
                    } else {
                        recvBooked = recvBooked.add(it.convertedBooked().abs());
                    }
                }
            }
            if (payBooked.compareTo(pc.booked) != 0) {
                throw new IllegalStateException("claim " + pc.claim.id()
                        + " payable trace " + payBooked + " != booked " + pc.booked);
            }
            if (recvBooked.compareTo(pc.booked) != 0) {
                throw new IllegalStateException("claim " + pc.claim.id()
                        + " receivable trace " + recvBooked + " != booked " + pc.booked);
            }
        }

        return new PlannedGroup(spec.code(), spec.name(), groupCurrency,
                mode == GroupMode.NET_CROSS_CURRENCY, false, spec.roundingBearerCode(),
                legs, exclusions, originalLegCount, gross, netAmount,
                roundingDiffTotal, BigDecimal.ZERO.setScale(MONEY_SCALE, RM),
                normalize(exactPositions), normalize(bookedPositions));
    }

    /** Local carrier of one included converted claim. */
    private static final class Included {
        final com.treasury.clearing.service.plan.PlannerClaim claim;
        final BigDecimal rate;
        final OffsetDateTime fxAsOf;
        final String fxSource;
        final boolean inverted;
        final BigDecimal booked;
        final BigDecimal exact;

        Included(com.treasury.clearing.service.plan.PlannerClaim claim, BigDecimal rate,
                 OffsetDateTime fxAsOf, String fxSource, boolean inverted,
                 BigDecimal booked, BigDecimal exact) {
            this.claim = claim;
            this.rate = rate;
            this.fxAsOf = fxAsOf;
            this.fxSource = fxSource;
            this.inverted = inverted;
            this.booked = booked;
            this.exact = exact;
        }
    }

    private static List<Slice> splitReal(List<Slice> source, BigDecimal targetCents,
                                         List<Slice> realSink, List<Slice> offsetSink) {
        // Greedy fill keeps the allocation exact at cent level: earlier claims fund
        // the real position fully, at most one claim is split, the rest is pure offset.
        BigDecimal allocated = BigDecimal.ZERO.setScale(MONEY_SCALE, RM);
        List<Slice> realCopies = new ArrayList<>();
        for (Slice s : source) {
            if (allocated.compareTo(targetCents) >= 0) {
                break;
            }
            BigDecimal give = s.bookedRemaining.min(targetCents.subtract(allocated));
            BigDecimal giveExact = s.exactRemaining.multiply(give)
                    .divide(s.bookedRemaining, EXACT_SCALE, RM);
            Slice real = new Slice(s.claim, s.side, s.rate, s.fxAsOf, s.fxSource, s.inverted,
                    give.setScale(MONEY_SCALE, RM), giveExact);
            s.bookedRemaining = s.bookedRemaining.subtract(give).setScale(MONEY_SCALE, RM);
            s.exactRemaining = s.exactRemaining.subtract(giveExact).setScale(EXACT_SCALE, RM);
            realSink.add(real);
            realCopies.add(real);
            allocated = allocated.add(give);
        }
        if (allocated.compareTo(targetCents) != 0) {
            throw new IllegalStateException("real allocation " + allocated + " != target " + targetCents);
        }
        for (Slice s : source) {
            if (s.bookedRemaining.signum() > 0) {
                offsetSink.add(s);
            }
        }
        return realCopies;
    }

    private static PlannedItem take(Slice s, BigDecimal bookedPart, String roundingBearer) {
        BigDecimal exactPart;
        if (bookedPart.compareTo(s.bookedRemaining) == 0) {
            exactPart = s.exactRemaining.setScale(EXACT_SCALE, RM);
        } else {
            exactPart = s.exactRemaining.multiply(bookedPart)
                    .divide(s.bookedRemaining, EXACT_SCALE, RM);
        }
        s.bookedRemaining = s.bookedRemaining.subtract(bookedPart).setScale(MONEY_SCALE, RM);
        s.exactRemaining = s.exactRemaining.subtract(exactPart).setScale(EXACT_SCALE, RM);

        BigDecimal originalShare = exactPart.divide(s.rate, EXACT_SCALE, RM);
        BigDecimal signedExact = s.side == LedgerSide.PAYER ? exactPart : exactPart.negate();
        BigDecimal signedBooked = s.side == LedgerSide.PAYER ? bookedPart : bookedPart.negate();
        BigDecimal roundingDiff = bookedPart.subtract(exactPart).setScale(EXACT_SCALE, RM);
        return new PlannedItem(s.claim.id(), s.claim.invoiceNo(),
                s.claim.debtorCode(), s.claim.creditorCode(), s.side,
                originalShare, s.claim.currency(),
                s.rate, s.fxAsOf, s.fxSource, s.inverted,
                signedExact, signedBooked, roundingDiff, roundingBearer);
    }

    private static Slice firstRemaining(List<Slice> q) {
        for (Slice c : q) {
            if (c.bookedRemaining.signum() > 0) {
                return c;
            }
        }
        return null;
    }

    private static List<String> sortedEntities(Map<String, BigDecimal> positions) {
        return new ArrayList<>(new java.util.TreeSet<>(positions.keySet()));
    }

    private static Map<String, BigDecimal> normalize(Map<String, BigDecimal> m) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        new java.util.TreeSet<>(m.keySet()).forEach(k ->
                out.put(k, m.get(k).setScale(EXACT_SCALE, RM)));
        return out;
    }

    private static PlannedGroup passThroughGroup(AgreementSpec spec, String groupCurrency,
                                                 List<PlannerClaim> ordered,
                                                 List<PlannedExclusion> exclusions,
                                                 BigDecimal gross, int originalLegCount) {
        List<PlannedLeg> legs = new ArrayList<>();
        int seq = 0;
        for (PlannerClaim c : ordered) {
            if (exclusions.stream().anyMatch(e -> e.claimId().equals(c.id()))) {
                continue;
            }
            PlannedItem item = new PlannedItem(c.id(), c.invoiceNo(), c.debtorCode(), c.creditorCode(),
                    LedgerSide.PAYER, c.amount(), c.currency(),
                    BigDecimal.ONE.setScale(10, RM), null, null, false,
                    c.amount().setScale(EXACT_SCALE, RM), c.amount().setScale(MONEY_SCALE, RM),
                    BigDecimal.ZERO.setScale(EXACT_SCALE, RM), spec.roundingBearerCode());
            legs.add(new PlannedLeg(c.debtorCode(), c.creditorCode(),
                    c.amount().setScale(MONEY_SCALE, RM),
                    c.amount().setScale(EXACT_SCALE, RM),
                    BigDecimal.ZERO.setScale(MONEY_SCALE, RM), true, ++seq, List.of(item)));
        }
        return new PlannedGroup(spec.code(), spec.name(), groupCurrency, false, true,
                spec.roundingBearerCode(), legs, exclusions, originalLegCount, gross, gross,
                BigDecimal.ZERO.setScale(EXACT_SCALE, RM), BigDecimal.ZERO.setScale(MONEY_SCALE, RM),
                Map.of(), Map.of());
    }

    private static String exclusionReason(AgreementSpec spec, PlannerClaim c, String groupCurrency,
                                          GroupMode mode) {
        if (c.debtorCode().equals(c.creditorCode())) {
            return "SELF_DEBT";
        }
        if (!spec.members().contains(c.debtorCode()) || !spec.members().contains(c.creditorCode())) {
            return "NOT_MEMBER_PAIR";
        }
        if (c.status() == ClaimStatus.PLEDGED) {
            return "PLEDGED";
        }
        if (c.status() == ClaimStatus.DISPUTED) {
            return "DISPUTED";
        }
        if (c.status() != ClaimStatus.OPEN) {
            return "NOT_OPEN";
        }
        if (!spec.currencies().isEmpty() && !spec.currencies().contains(c.currency())) {
            return "CCY_NOT_ALLOWED";
        }
        if (mode == GroupMode.NET_SAME_CURRENCY && !c.currency().equals(groupCurrency)) {
            return "CROSS_CCY_NOT_ALLOWED";
        }
        return null;
    }

    private static String exclusionDetail(AgreementSpec spec, PlannerClaim c, String groupCurrency,
                                          GroupMode mode, String reason) {
        return switch (reason) {
            case "PLEDGED" -> "claim is pledged as collateral; set-off prohibited";
            case "DISPUTED" -> "claim is under dispute; excluded from netting";
            case "NOT_MEMBER_PAIR" -> "debtor/creditor not both members of agreement " + spec.code();
            case "CCY_NOT_ALLOWED" -> c.currency() + " is outside agreement currency scope";
            case "CROSS_CCY_NOT_ALLOWED" ->
                    "agreement forbids cross-currency set-off (" + c.currency() + " vs " + groupCurrency + ")";
            case "NOT_OPEN" -> "claim status is " + c.status();
            case "SELF_DEBT" -> "debtor and creditor are the same entity";
            default -> null;
        };
    }
}
