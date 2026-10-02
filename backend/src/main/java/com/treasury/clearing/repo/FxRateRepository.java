package com.treasury.clearing.repo;

import com.treasury.clearing.domain.FxRate;
import com.treasury.clearing.domain.FxRateId;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface FxRateRepository extends JpaRepository<FxRate, FxRateId> {

    Pageable LATEST_ONE = PageRequest.of(0, 1);

    /** Latest snapshot of base→quote at or before asOf. */
    @Query("""
            select r from FxRate r
            where r.baseCurrency = :base and r.quoteCurrency = :quote and r.asOf <= :asOf
            order by r.asOf desc
            """)
    java.util.List<FxRate> findRates(@Param("base") String base, @Param("quote") String quote,
                                     @Param("asOf") OffsetDateTime asOf, Pageable pageable);

    default Optional<FxRate> findRate(String base, String quote, OffsetDateTime asOf) {
        return findRates(base, quote, asOf, LATEST_ONE).stream().findFirst();
    }
}
