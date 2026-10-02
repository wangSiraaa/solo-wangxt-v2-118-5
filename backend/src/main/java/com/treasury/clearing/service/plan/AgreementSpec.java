package com.treasury.clearing.service.plan;

import java.math.BigDecimal;
import java.util.Set;

/** Static protocol boundary of one agreement as seen by the planner. */
public record AgreementSpec(String code,
                            String name,
                            boolean allowsNetting,
                            boolean crossCurrency,
                            Set<String> members,
                            /** Empty set = all currencies allowed. */
                            Set<String> currencies,
                            String settlementCurrency,
                            String roundingBearerCode) {
}
