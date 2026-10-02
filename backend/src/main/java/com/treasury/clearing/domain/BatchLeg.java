package com.treasury.clearing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A single payment leg of the (possibly netted) result:
 * payer pays {@code amount} {@code settlementCurrency} to receiver.
 * {@code original} legs are un-netted pass-through copies of one claim;
 * netted legs carry many source items (and thus zero-sum verification data).
 */
@Entity
@Table(name = "batch_leg")
public class BatchLeg {

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private BatchGroup group;

    @Column(name = "payer_code", nullable = false, length = 32)
    private String payerCode;

    @Column(name = "receiver_code", nullable = false, length = 32)
    private String receiverCode;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(name = "settlement_ccy", nullable = false, length = 3)
    private String settlementCurrency;

    /** false = netted leg aggregating several claims; true = untouched original debt. */
    @Column(name = "is_original", nullable = false)
    private boolean original;

    /** Exact (unrounded, 6dp) amount the legs derived from, before the residual fix-up. */
    @Column(name = "amount_exact", precision = 20, scale = 6)
    private BigDecimal amountExact;

    /** Residual adjustment applied to this leg (non-zero only on the rounding-bearer leg). */
    @Column(name = "residual_adjustment", nullable = false, precision = 20, scale = 2)
    private BigDecimal residualAdjustment = BigDecimal.ZERO;

    @Column(name = "seq_no", nullable = false)
    private int seqNo;

    @OneToMany(mappedBy = "leg", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LegItem> items = new ArrayList<>();

    protected BatchLeg() {
    }

    public BatchLeg(String id, String payerCode, String receiverCode, BigDecimal amount,
                    String settlementCurrency, boolean original, BigDecimal amountExact,
                    BigDecimal residualAdjustment, int seqNo) {
        this.id = id;
        this.payerCode = payerCode;
        this.receiverCode = receiverCode;
        this.amount = amount;
        this.settlementCurrency = settlementCurrency;
        this.original = original;
        this.amountExact = amountExact;
        this.residualAdjustment = residualAdjustment;
        this.seqNo = seqNo;
    }

    public void setGroup(BatchGroup group) {
        this.group = group;
    }

    public void addItem(LegItem item) {
        items.add(item);
        item.setLeg(this);
    }

    public String getId() {
        return id;
    }

    public BatchGroup getGroup() {
        return group;
    }

    public String getPayerCode() {
        return payerCode;
    }

    public String getReceiverCode() {
        return receiverCode;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getSettlementCurrency() {
        return settlementCurrency;
    }

    public boolean isOriginal() {
        return original;
    }

    public BigDecimal getAmountExact() {
        return amountExact;
    }

    public BigDecimal getResidualAdjustment() {
        return residualAdjustment;
    }

    public int getSeqNo() {
        return seqNo;
    }

    public List<LegItem> getItems() {
        return items;
    }

    /** Offset timestamp copied onto items when simulated payments are run. */
    @Column(name = "paid_simulated_at")
    private OffsetDateTime paidSimulatedAt;

    public void markPaidSimulated(OffsetDateTime at) {
        this.paidSimulatedAt = at;
    }

    public OffsetDateTime getPaidSimulatedAt() {
        return paidSimulatedAt;
    }
}
