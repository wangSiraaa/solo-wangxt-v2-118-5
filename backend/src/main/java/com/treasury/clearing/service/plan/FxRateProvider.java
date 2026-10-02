package com.treasury.clearing.service.plan;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Resolves 1 unit of {@code from} in units of {@code to} at (or before) {@code asOf}. */
public interface FxRateProvider {

    Optional<FxQuote> quote(String from, String to, OffsetDateTime asOf);
}
