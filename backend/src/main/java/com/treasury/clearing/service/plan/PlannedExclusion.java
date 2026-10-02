package com.treasury.clearing.service.plan;

public record PlannedExclusion(String claimId,
                               String invoiceNo,
                               String reasonCode,
                               String reasonDetail) {
}
