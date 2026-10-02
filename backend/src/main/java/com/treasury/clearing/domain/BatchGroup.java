package com.treasury.clearing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One (agreement, settlement currency) pocket inside a batch.
 * When the agreement forbids netting the group is "pass-through":
 * every original claim survives as its own leg, unchanged.
 */
@Entity
@Table(name = "batch_group")
public class BatchGroup {

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id")
    private NettingBatch batch;

    @Column(name = "agreement_code", nullable = false, length = 32)
    private String agreementCode;

    @Column(name = "agreement_name", length = 128)
    private String agreementName;

    @Column(name = "settlement_ccy", nullable = false, length = 3)
    private String settlementCurrency;

    @Column(name = "cross_currency", nullable = false)
    private boolean crossCurrency;

    /** true = set-off not allowed: original debts are preserved 1:1. */
    @Column(name = "pass_through", nullable = false)
    private boolean passThrough;

    @Column(name = "original_leg_count", nullable = false)
    private int originalLegCount;

    @Column(name = "netted_leg_count", nullable = false)
    private int nettedLegCount;

    @Column(name = "gross_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal netAmount = BigDecimal.ZERO;

    /** Sum of per-item rounding differences absorbed, in settlement currency. */
    @Column(name = "rounding_diff_total", nullable = false, precision = 20, scale = 6)
    private BigDecimal roundingDiffTotal = BigDecimal.ZERO;

    /** Residual of net positions after cent-rounding, attributed to one entity. */
    @Column(name = "rounding_residual", nullable = false, precision = 20, scale = 2)
    private BigDecimal roundingResidual = BigDecimal.ZERO;

    @Column(name = "rounding_bearer_code", length = 32)
    private String roundingBearerCode;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BatchLeg> legs = new ArrayList<>();

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BatchExclusion> exclusions = new ArrayList<>();

    protected BatchGroup() {
    }

    public BatchGroup(String id, String agreementCode, String agreementName, String settlementCurrency,
                      boolean crossCurrency, boolean passThrough, String roundingBearerCode) {
        this.id = id;
        this.agreementCode = agreementCode;
        this.agreementName = agreementName;
        this.settlementCurrency = settlementCurrency;
        this.crossCurrency = crossCurrency;
        this.passThrough = passThrough;
        this.roundingBearerCode = roundingBearerCode;
    }

    public void setBatch(NettingBatch batch) {
        this.batch = batch;
    }

    public void addLeg(BatchLeg leg) {
        legs.add(leg);
        leg.setGroup(this);
    }

    public void addExclusion(BatchExclusion exclusion) {
        exclusions.add(exclusion);
        exclusion.setGroup(this);
    }

    public void setStats(int originalLegCount, int nettedLegCount, BigDecimal grossAmount,
                         BigDecimal netAmount, BigDecimal roundingDiffTotal, BigDecimal roundingResidual) {
        this.originalLegCount = originalLegCount;
        this.nettedLegCount = nettedLegCount;
        this.grossAmount = grossAmount.setScale(2, java.math.RoundingMode.HALF_UP);
        this.netAmount = netAmount.setScale(2, java.math.RoundingMode.HALF_UP);
        this.roundingDiffTotal = roundingDiffTotal.setScale(6, java.math.RoundingMode.HALF_UP);
        this.roundingResidual = roundingResidual.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public String getId() {
        return id;
    }

    public NettingBatch getBatch() {
        return batch;
    }

    public String getAgreementCode() {
        return agreementCode;
    }

    public String getAgreementName() {
        return agreementName;
    }

    public String getSettlementCurrency() {
        return settlementCurrency;
    }

    public boolean isCrossCurrency() {
        return crossCurrency;
    }

    public boolean isPassThrough() {
        return passThrough;
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

    public BigDecimal getRoundingDiffTotal() {
        return roundingDiffTotal;
    }

    public BigDecimal getRoundingResidual() {
        return roundingResidual;
    }

    public String getRoundingBearerCode() {
        return roundingBearerCode;
    }

    public List<BatchLeg> getLegs() {
        return legs;
    }

    public List<BatchExclusion> getExclusions() {
        return exclusions;
    }
}
