package com.treasury.clearing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A netting trial / clearing batch. One batch may contain several groups —
 * one group per (agreement, settlement currency). Groups are deliberately
 * isolated: offsets can never cross an agreement or currency boundary.
 */
@Entity
@Table(name = "netting_batch")
public class NettingBatch {

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "valuation_date", nullable = false)
    private LocalDate valuationDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchStatus status = BatchStatus.SIMULATED;

    @Column(name = "incoming_claim_count", nullable = false)
    private int incomingClaimCount;

    @Column(name = "included_claim_count", nullable = false)
    private int includedClaimCount;

    @Column(name = "excluded_claim_count", nullable = false)
    private int excludedClaimCount;

    /** Number of payment legs the debts would need without netting (one per included claim direction). */
    @Column(name = "original_leg_count", nullable = false)
    private int originalLegCount;

    /** Number of payment legs after netting. */
    @Column(name = "netted_leg_count", nullable = false)
    private int nettedLegCount;

    /** Sum of absolute gross flows in each group's settlement currency (group label prefixed). */
    @Column(name = "gross_amount", precision = 20, scale = 2)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", precision = 20, scale = 2)
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "paid_simulated_at")
    private OffsetDateTime paidSimulatedAt;

    @Column(length = 256)
    private String note;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BatchGroup> groups = new ArrayList<>();

    protected NettingBatch() {
    }

    public NettingBatch(String id, LocalDate valuationDate, OffsetDateTime createdAt, String note) {
        this.id = id;
        this.valuationDate = valuationDate;
        this.createdAt = createdAt;
        this.note = note;
    }

    public void addGroup(BatchGroup group) {
        groups.add(group);
        group.setBatch(this);
    }

    public String getId() {
        return id;
    }

    public LocalDate getValuationDate() {
        return valuationDate;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public int getIncomingClaimCount() {
        return incomingClaimCount;
    }

    public int getIncludedClaimCount() {
        return includedClaimCount;
    }

    public int getExcludedClaimCount() {
        return excludedClaimCount;
    }

    public int getOriginalLegCount() {
        return originalLegCount;
    }

    public int getNettedLegCount() {
        return nettedLegCount;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public OffsetDateTime getPaidSimulatedAt() {
        return paidSimulatedAt;
    }

    public String getNote() {
        return note;
    }

    public List<BatchGroup> getGroups() {
        return groups;
    }

    /** Mutators used by the planning service when persisting. */
    public void setCounters(int incoming, int included, int excluded, int originalLegs, int nettedLegs,
                     BigDecimal gross, BigDecimal net) {
        this.incomingClaimCount = incoming;
        this.includedClaimCount = included;
        this.excludedClaimCount = excluded;
        this.originalLegCount = originalLegs;
        this.nettedLegCount = nettedLegs;
        this.grossAmount = gross;
        this.netAmount = net;
    }

    public void confirm(OffsetDateTime at) {
        this.status = BatchStatus.CONFIRMED;
        this.confirmedAt = at;
    }

    public void markPaidSimulated(OffsetDateTime at) {
        this.status = BatchStatus.PAID_SIMULATED;
        this.paidSimulatedAt = at;
    }
}
