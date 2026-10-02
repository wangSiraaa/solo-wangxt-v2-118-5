package com.treasury.clearing.service.plan;

import java.math.BigDecimal;
import java.util.List;

/** Result of netting one (agreement, settlement currency) pocket. */
public record PlannedGroup(String agreementCode,
                           String agreementName,
                           String settlementCurrency,
                           boolean crossCurrency,
                           boolean passThrough,
                           String roundingBearerCode,
                           List<PlannedLeg> legs,
                           List<PlannedExclusion> exclusions,
                           int originalLegCount,
                           BigDecimal grossAmount,
                           BigDecimal netAmount,
                           BigDecimal roundingDiffTotal,
                           BigDecimal roundingResidual,
                           /** Exact net positions per entity (payable positive). */
                           java.util.Map<String, BigDecimal> exactPositions,
                           /** Booked (2dp) net positions per entity. */
                           java.util.Map<String, BigDecimal> bookedPositions) {
}
