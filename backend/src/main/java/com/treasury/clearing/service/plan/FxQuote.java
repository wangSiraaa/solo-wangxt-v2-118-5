package com.treasury.clearing.service.plan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** The FX quote actually applied for a conversion, including audit metadata. */
public record FxQuote(BigDecimal rate,
                      OffsetDateTime asOf,
                      String source,
                      boolean inverted,
                      BigDecimal margin) {
}
