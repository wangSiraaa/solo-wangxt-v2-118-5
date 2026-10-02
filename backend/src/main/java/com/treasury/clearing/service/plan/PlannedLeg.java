package com.treasury.clearing.service.plan;

import java.math.BigDecimal;
import java.util.List;

/**
 * A resulting payment leg. {@code residualAdjustment} is non-zero only when the
 * cent-rounded legs would not sum to zero and the residual is pushed onto the
 * agreement's rounding bearer; with per-item cent booking it is normally zero.
 */
public record PlannedLeg(String payerCode,
                         String receiverCode,
                         BigDecimal amount,
                         BigDecimal amountExact,
                         BigDecimal residualAdjustment,
                         boolean original,
                         int seqNo,
                         List<PlannedItem> items) {
}
