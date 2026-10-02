package com.treasury.clearing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * An original intercompany debt (typically backed by an invoice):
 * debtor owes creditor {@code amount} in {@code currency}.
 * This row is never rewritten by netting — offsets are recorded separately,
 * which lets the treasurer trace every offset leg back to its source claim.
 */
@Entity
@Table(name = "claim")
public class Claim {

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "invoice_no", length = 64)
    private String invoiceNo;

    @Column(name = "agreement_code", nullable = false, length = 32)
    private String agreementCode;

    @Column(name = "debtor_code", nullable = false, length = 32)
    private String debtorCode;

    @Column(name = "creditor_code", nullable = false, length = 32)
    private String creditorCode;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClaimStatus status = ClaimStatus.OPEN;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(length = 256)
    private String description;

    /** Set once a confirmed batch offset this claim; null while untouched. */
    @Column(name = "offset_batch_id", length = 40)
    private String offsetBatchId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-30T00:00:00Z");

    protected Claim() {
    }

    public Claim(String id, String invoiceNo, String agreementCode, String debtorCode, String creditorCode,
                 BigDecimal amount, String currency, ClaimStatus status,
                 LocalDate invoiceDate, LocalDate dueDate, String description) {
        this.id = id;
        this.invoiceNo = invoiceNo;
        this.agreementCode = agreementCode;
        this.creditorCode = creditorCode;
        this.debtorCode = debtorCode;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.invoiceDate = invoiceDate;
        this.dueDate = dueDate;
        this.description = description;
    }

    private void creditorCodeRaw(String c) {
        this.creditorCode = c;
    }

    public String getId() {
        return id;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public String getAgreementCode() {
        return agreementCode;
    }

    public String getDebtorCode() {
        return debtorCode;
    }

    public String getCreditorCode() {
        return creditorCode;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getDescription() {
        return description;
    }

    public String getOffsetBatchId() {
        return offsetBatchId;
    }

    public void markOffset(String batchId) {
        this.offsetBatchId = batchId;
        this.status = ClaimStatus.SETTLED;
    }
}
