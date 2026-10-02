package com.treasury.clearing.service;

import com.treasury.clearing.repo.FxRateRepository;
import com.treasury.clearing.service.plan.FxQuote;
import com.treasury.clearing.service.plan.FxRateProvider;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Resolves FX through the stored snapshots: direct pair, else exact inverse.
 * "Exact" because BigDecimal division is done by the planner at conversion time
 * with controlled scale; here we only flag inversion and keep the stored rate.
 */
@Component
public class JpaFxRateProvider implements FxRateProvider {

    private final FxRateRepository fxRateRepository;

    public JpaFxRateProvider(FxRateRepository fxRateRepository) {
        this.fxRateRepository = fxRateRepository;
    }

    @Override
    public Optional<FxQuote> quote(String from, String to, OffsetDateTime asOf) {
        if (from.equals(to)) {
            return Optional.of(new FxQuote(java.math.BigDecimal.ONE, asOf, "SAME_CCY", false, null));
        }
        OffsetDateTime pointInTime = asOf != null ? asOf : OffsetDateTime.now();
        Optional<com.treasury.clearing.domain.FxRate> direct =
                fxRateRepository.findRate(from, to, pointInTime);
        if (direct.isPresent()) {
            var r = direct.get();
            return Optional.of(new FxQuote(r.getRate(), r.getAsOf(), r.getSource(), false, null));
        }
        Optional<com.treasury.clearing.domain.FxRate> inverse =
                fxRateRepository.findRate(to, from, pointInTime);
        return inverse.map(r -> new FxQuote(
                java.math.BigDecimal.ONE.divide(r.getRate(), 10, java.math.RoundingMode.HALF_UP),
                r.getAsOf(), r.getSource() + "(inverse)", true, null));
    }
}
