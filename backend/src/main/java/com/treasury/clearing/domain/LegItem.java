package com.treasury.clearing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Trace line: exactly how one original claim contributed to a netted leg.
 * For cross-currency claims the row lists the rate used, the rate timestamp
 * (the FX snapshot point in time), the exact conversion, the booked conversion
 * and the per-item rounding difference and who bears it.
 */
@Entity
@Table(name = "leg_item")
public class LegItem {

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leg_id")
    private BatchLeg leg;

    @Column(name = "claim_id", nullable = false, length = 40)
    private String claimId;

    @Column(name = "invoice_no", length = 64)
    private String invoiceNo;

    @Column(name = "debtor_code", nullable = false, length = 32)
    private String debtorCode;

    @Column(name = "creditor_code", nullable = false, length = 32)
    private String creditorCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private LedgerSide side;

    @Column(name = "original_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "original_ccy", nullable = false, length = 3)
    private String originalCurrency;

    /** 1 original_ccy = fxRate settlement_ccy. 1 for same-currency. */
    @Column(name = "fx_rate", precision = 20, scale = 10)
    private BigDecimal fxRate;

    @Column(name = "fx_as_of")
    private OffsetDateTime fxAsOf;

    @Column(name = "fx_source", length = 32)
    private String fxSource;

    /** True when the stored pair was inverted to convert through settlement currency. */
    @Column(name = "fx_inverted", nullable = false)
    private boolean fxInverted;

    /** Conversion at full precision (6 dp). */
    @Column(name = "converted_exact", nullable = false, precision = 20, scale = 6)
    private BigDecimal convertedExact;

    /** Conversion booked into the net position (2 dp). */
    @Column(name = "converted_booked", nullable = false, precision = 20, scale = 2)
    private BigDecimal convertedBooked;

    /** booked - exact (the small rounding difference of this item). */
    @Column(name = "rounding_diff", nullable = false, precision = 20, scale = 6)
    private BigDecimal roundingDiff;

    /** Entity that bears this item's rounding difference. */
    @Column(name = "rounding_bearer_code", length = 32)
    private String roundingBearerCode;

    protected LegItem() {
    }

    public LegItem(String id, String claimId, String invoiceNo, String debtorCode, String creditorCode,
                   LedgerSide side, BigDecimal originalAmount, String originalCurrency,
                   BigDecimal fxRate, OffsetDateTime fxAsOf, String fxSource, boolean fxInverted,
                   BigDecimal convertedExact, BigDecimal convertedBooked, BigDecimal roundingDiff,
                   String roundingBearerCode) {
        this.id = id;
        this.claimId = claimId;
        this.invoiceNo = invoiceNo;
        this.debtorCode = debtorCode;
        this.creditorCode = creditorCode;
        this.side = side;
        this.originalAmount = originalAmount;
        this.originalCurrency = originalCurrency;
        this.fxRate = fxRate;
        this.fxAsOf = fxAsOf;
        this.fxSource = fxSource;
        this.fxInverted = fxInverted;
        this.convertedExact = convertedExact;
        this.convertedBooked = convertedBooked;
        this.roundingDiff = roundingDiff;
        this.roundingBearerCode = roundingBearerCode;
    }

    public void setLeg(BatchLeg leg) {
        this.leg = leg;
    }

    public String getId() {
        return id;
    }

    public BatchLeg getLeg() {
        return leg;
    }

    public String getClaimId() {
        return claimId;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public String getDebtorCode() {
        return debtorCode;
    }

    public String getCreditorCode() {
        return creditorCode;
    }

    public LedgerSide getSide() {
        return side;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public String getOriginalCurrency() {
        return originalCurrency;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public OffsetDateTime getFxAsOf() {
        return fxAsOf;
    }

    public String getFxSource() {
        return fxSource;
    }

    public boolean isFxInverted() {
        return fxInverted;
    }

    public BigDecimal getConvertedExact() {
        return convertedExact;
    }

    public BigDecimal getConvertedBooked() {
        return convertedBooked;
    }

    public BigDecimal getRoundingDiff() {
        return roundingDiff;
    }

    public String getRoundingBearerCode() {
        return roundingBearerCode;
    }
}
