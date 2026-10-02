package com.treasury.clearing.domain;

import jakarta.persistence.*;

/** A claim that was considered but kept out of the netting, with the exact reason. */
@Entity
@Table(name = "batch_exclusion")
public class BatchExclusion {

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private BatchGroup group;

    @Column(name = "claim_id", nullable = false, length = 40)
    private String claimId;

    @Column(name = "invoice_no", length = 64)
    private String invoiceNo;

    /**
     * PLEDGED, DISPUTED, NOT_OPEN, NOT_MEMBER_PAIR, CCY_NOT_ALLOWED,
     * CROSS_CCY_NOT_ALLOWED, FX_RATE_MISSING, AGREEMENT_INACTIVE, SELF_DEBT
     */
    @Column(name = "reason_code", nullable = false, length = 32)
    private String reasonCode;

    @Column(name = "reason_detail", length = 256)
    private String reasonDetail;

    protected BatchExclusion() {
    }

    public BatchExclusion(String id, String claimId, String invoiceNo, String reasonCode, String reasonDetail) {
        this.id = id;
        this.claimId = claimId;
        this.invoiceNo = invoiceNo;
        this.reasonCode = reasonCode;
        this.reasonDetail = reasonDetail;
    }

    public void setGroup(BatchGroup group) {
        this.group = group;
    }

    public String getId() {
        return id;
    }

    public String getClaimId() {
        return claimId;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonDetail() {
        return reasonDetail;
    }
}
