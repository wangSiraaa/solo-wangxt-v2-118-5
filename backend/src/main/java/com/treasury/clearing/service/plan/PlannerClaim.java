package com.treasury.clearing.service.plan;

import com.treasury.clearing.domain.ClaimStatus;

import java.math.BigDecimal;

/** Immutable snapshot of an original claim as seen by the planner. */
public record PlannerClaim(String id,
                           String invoiceNo,
                           String agreementCode,
                           String debtorCode,
                           String creditorCode,
                           BigDecimal amount,
                           String currency,
                           ClaimStatus status,
                           String description) {
}
