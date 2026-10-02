package com.treasury.clearing.service.plan;

import com.treasury.clearing.domain.LedgerSide;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One original claim's contribution to a result leg.
 * FX fields are populated for cross-currency items (same-currency: rate=1, timestamps null).
 */
public record PlannedItem(String claimId,
                          String invoiceNo,
                          String debtorCode,
                          String creditorCode,
                          LedgerSide side,
                          BigDecimal originalAmount,
                          String originalCurrency,
                          BigDecimal fxRate,
                          OffsetDateTime fxAsOf,
                          String fxSource,
                          boolean fxInverted,
                          BigDecimal convertedExact,
                          BigDecimal convertedBooked,
                          BigDecimal roundingDiff,
                          String roundingBearerCode) {
}
