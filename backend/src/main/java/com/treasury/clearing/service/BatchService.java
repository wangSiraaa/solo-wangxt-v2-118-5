package com.treasury.clearing.service;

import com.treasury.clearing.domain.*;
import com.treasury.clearing.repo.*;
import com.treasury.clearing.service.plan.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Orchestrates trial runs: groups claims by protocol pocket ((agreement, currency)),
 * runs the pure {@link NettingPlanner}, and persists the result for audit.
 * Simulation never mutates original claims; only an explicit confirm does, and even
 * "payment" is a simulated marker — no bank channel exists.
 */
@Service
public class BatchService {

    private final NettingAgreementRepository agreementRepository;
    private final ClaimRepository claimRepository;
    private final NettingBatchRepository batchRepository;
    private final JpaFxRateProvider fxProvider;

    public BatchService(NettingAgreementRepository agreementRepository,
                        ClaimRepository claimRepository,
                        NettingBatchRepository batchRepository,
                        JpaFxRateProvider fxProvider) {
        this.agreementRepository = agreementRepository;
        this.claimRepository = claimRepository;
        this.batchRepository = batchRepository;
        this.fxProvider = fxProvider;
    }

    public record SimulationRequest(LocalDate valuationDate,
                                    List<String> agreementCodes, // null/empty = all active
                                    String note) {
    }

    @Transactional
    public NettingBatch simulate(SimulationRequest request) {
        LocalDate valuationDate = request.valuationDate() != null ? request.valuationDate() : LocalDate.now();
        List<NettingAgreement> agreements = agreementRepository.findAll().stream()
                .filter(a -> isActiveOn(a, valuationDate))
                .filter(a -> request.agreementCodes() == null || request.agreementCodes().isEmpty()
                        || request.agreementCodes().contains(a.getCode()))
                .sorted(Comparator.comparing(NettingAgreement::getCode))
                .toList();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String batchId = "B" + valuationDate.toString().replace("-", "")
                + "-" + Integer.toHexString(now.toLocalTime().toSecondOfDay())
                + "-" + UUID.randomUUID().toString().substring(0, 6);
        NettingBatch batch = new NettingBatch(batchId, valuationDate, now, request.note());

        int incoming = 0;
        int included = 0;
        int excluded = 0;
        int originalLegs = 0;
        int nettedLegs = 0;
        BigDecimal gross = BigDecimal.ZERO.setScale(2);
        BigDecimal net = BigDecimal.ZERO.setScale(2);
        int gSeq = 0;

        for (NettingAgreement a : agreements) {
            List<Claim> agreementClaims = claimRepository.findByAgreementCodeOrderByInvoiceNo(a.getCode());
            incoming += agreementClaims.size();
            if (agreementClaims.isEmpty()) {
                continue;
            }

            // One group per (agreement, settlement currency).
            Set<String> currencies = new TreeSet<>();
            for (Claim c : agreementClaims) {
                currencies.add(c.getCurrency());
            }
            if (!a.isAllowsNetting() || !a.isCrossCurrency()) {
                // Without cross-currency permission every currency is its own pocket.
                for (String ccy : currencies) {
                    GroupMode mode = a.isAllowsNetting()
                            ? GroupMode.NET_SAME_CURRENCY
                            : GroupMode.PASS_THROUGH;
                    PlannedGroup pg = runPocket(a, ccy, mode, agreementClaims, valuationDate, now);
                    if (pg == null) {
                        continue;
                    }
                    persistGroup(batch, pg, a, batchId, ++gSeq);
                    included += pg.originalLegCount();
                    excluded += pg.exclusions().size();
                    originalLegs += pg.originalLegCount();
                    nettedLegs += countCashLegs(pg);
                    gross = gross.add(pg.grossAmount());
                    net = net.add(pg.netAmount());
                }
            } else {
                String settlement = a.getSettlementCurrency();
                if (settlement == null || settlement.isBlank()) {
                    throw new IllegalArgumentException(
                            "cross-currency agreement " + a.getCode() + " has no settlement currency");
                }
                PlannedGroup pg = runPocket(a, settlement, GroupMode.NET_CROSS_CURRENCY,
                        agreementClaims, valuationDate, now);
                if (pg == null) {
                    continue;
                }
                persistGroup(batch, pg, a, batchId, ++gSeq);
                included += pg.originalLegCount();
                excluded += pg.exclusions().size();
                originalLegs += pg.originalLegCount();
                nettedLegs += countCashLegs(pg);
                gross = gross.add(pg.grossAmount());
                net = net.add(pg.netAmount());
            }
        }

        batch.setCounters(incoming, included, excluded, originalLegs, nettedLegs,
                gross.setScale(2, java.math.RoundingMode.HALF_UP),
                net.setScale(2, java.math.RoundingMode.HALF_UP));
        return batchRepository.save(batch);
    }

    private int countCashLegs(PlannedGroup pg) {
        int n = 0;
        for (PlannedLeg l : pg.legs()) {
            // Zero-amount set-off memos are trace records, not payments.
            if (l.amount().signum() != 0) {
                n++;
            }
        }
        return n;
    }

    private PlannedGroup runPocket(NettingAgreement a, String groupCurrency, GroupMode mode,
                                   List<Claim> agreementClaims, LocalDate valuationDate,
                                   OffsetDateTime now) {
        AgreementSpec spec = new AgreementSpec(a.getCode(), a.getName(), a.isAllowsNetting(),
                a.isCrossCurrency(), new HashSet<>(a.getMembers()), new HashSet<>(a.getCurrencies()),
                groupCurrency, a.getRoundingBearerCode());
        List<PlannerClaim> plannerClaims = agreementClaims.stream()
                .map(c -> new PlannerClaim(c.getId(), c.getInvoiceNo(), c.getAgreementCode(),
                        c.getDebtorCode(), c.getCreditorCode(), c.getAmount(), c.getCurrency(),
                        c.getStatus(), c.getDescription()))
                .toList();

        OffsetDateTime fxAsOf = a.getFxAsOf() != null
                ? a.getFxAsOf()
                : valuationDate.atTime(23, 59, 59).atOffset(ZoneOffset.UTC);
        PlannedGroup pg = NettingPlanner.plan(spec, plannerClaims, groupCurrency, mode,
                fxProvider, fxAsOf, a.getFxMargin());
        if (pg.legs().isEmpty() && pg.exclusions().isEmpty()) {
            return null;
        }
        return pg;
    }

    private void persistGroup(NettingBatch batch, PlannedGroup pg, NettingAgreement a,
                              String batchId, int gSeq) {
        String groupId = batchId + "-G" + gSeq;
        BatchGroup group = new BatchGroup(groupId, pg.agreementCode(), pg.agreementName(),
                pg.settlementCurrency(), pg.crossCurrency(), pg.passThrough(),
                pg.roundingBearerCode());
        batch.addGroup(group);
        group.setStats(pg.originalLegCount(), countCashLegs(pg), pg.grossAmount(),
                pg.netAmount(), pg.roundingDiffTotal(), pg.roundingResidual());

        for (PlannedLeg pl : pg.legs()) {
            String legId = groupId + "-L" + pl.seqNo();
            BatchLeg leg = new BatchLeg(legId, pl.payerCode(), pl.receiverCode(),
                    pl.amount().setScale(2, java.math.RoundingMode.HALF_UP),
                    pg.settlementCurrency(), pl.original(), pl.amountExact(),
                    pl.residualAdjustment(), pl.seqNo());
            group.addLeg(leg);
            int iSeq = 0;
            for (PlannedItem it : pl.items()) {
                leg.addItem(new LegItem(legId + "-I" + (++iSeq),
                        it.claimId(), it.invoiceNo(), it.debtorCode(), it.creditorCode(),
                        it.side(), it.originalAmount(), it.originalCurrency(),
                        it.fxRate(), it.fxAsOf(), it.fxSource(), it.fxInverted(),
                        it.convertedExact(), it.convertedBooked(), it.roundingDiff(),
                        it.roundingBearerCode()));
            }
        }
        int eSeq = 0;
        for (PlannedExclusion ex : pg.exclusions()) {
            group.addExclusion(new BatchExclusion(groupId + "-E" + (++eSeq),
                    ex.claimId(), ex.invoiceNo(), ex.reasonCode(), ex.reasonDetail()));
        }
    }

    @Transactional
    public NettingBatch confirm(String batchId) {
        NettingBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("batch not found: " + batchId));
        if (batch.getStatus() != BatchStatus.SIMULATED) {
            throw new IllegalStateException("only a SIMULATED batch can be confirmed; current="
                    + batch.getStatus());
        }
        // Confirmed set-off books: claims covered by netted (non-pass-through) groups are
        // marked settled by the offset. Pass-through groups settle nothing at confirmation.
        Set<String> nettedClaimIds = new LinkedHashSet<>();
        for (BatchGroup g : batch.getGroups()) {
            if (g.isPassThrough()) {
                continue;
            }
            for (BatchLeg leg : g.getLegs()) {
                for (LegItem item : leg.getItems()) {
                    // A claim partially restored as an original surviving leg is not fully
                    // offset; only mark claims that have no surviving-original leg.
                    nettedClaimIds.add(item.getClaimId());
                }
            }
        }
        for (BatchGroup g : batch.getGroups()) {
            if (g.isPassThrough()) {
                for (BatchLeg leg : g.getLegs()) {
                    leg.getItems().forEach(i -> nettedClaimIds.remove(i.getClaimId()));
                }
            }
        }
        for (String claimId : nettedClaimIds) {
            Claim claim = claimRepository.findById(claimId).orElseThrow();
            if (claim.getStatus() != ClaimStatus.OPEN || claim.getOffsetBatchId() != null) {
                throw new IllegalStateException("claim " + claimId
                        + " is no longer open; batch confirmation aborted");
            }
            claim.markOffset(batchId);
        }
        batch.confirm(OffsetDateTime.now(ZoneOffset.UTC));
        return batch;
    }

    @Transactional
    public NettingBatch markPaidSimulated(String batchId) {
        NettingBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("batch not found: " + batchId));
        if (batch.getStatus() != BatchStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "only a CONFIRMED batch can enter simulated payment; current=" + batch.getStatus());
        }
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
        for (BatchGroup g : batch.getGroups()) {
            for (BatchLeg leg : g.getLegs()) {
                if (leg.getAmount().signum() > 0 && leg.getPaidSimulatedAt() == null) {
                    leg.markPaidSimulated(at);
                }
            }
        }
        batch.markPaidSimulated(at);
        return batch;
    }

    /**
     * Loads the whole batch graph inside one transaction and initializes every lazy
     * collection level by level (Hibernate cannot eagerly fetch two bags at once).
     */
    @Transactional(readOnly = true)
    public NettingBatch getBatch(String batchId) {
        NettingBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("batch not found: " + batchId));
        org.hibernate.Hibernate.initialize(batch.getGroups());
        for (BatchGroup g : batch.getGroups()) {
            org.hibernate.Hibernate.initialize(g.getLegs());
            org.hibernate.Hibernate.initialize(g.getExclusions());
            // Touch every element so its data is actually loaded before the tx ends.
            g.getLegs().size();
            g.getExclusions().size();
            for (BatchLeg leg : g.getLegs()) {
                org.hibernate.Hibernate.initialize(leg.getItems());
                leg.getItems().size();
            }
        }
        return batch;
    }

    @Transactional(readOnly = true)
    public List<NettingBatch> listBatches() {
        return batchRepository.findAllByOrderByCreatedAtDesc();
    }

    private boolean isActiveOn(NettingAgreement a, LocalDate date) {
        return (a.getEffectiveFrom() == null || !date.isBefore(a.getEffectiveFrom()))
                && (a.getEffectiveTo() == null || !date.isAfter(a.getEffectiveTo()));
    }
}
