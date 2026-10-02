package com.treasury.clearing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * FX rate snapshot: 1 unit of base currency = {@code rate} units of quote currency
 * at {@code asOf}. The netting planner always converts through settlement currency,
 * using the direct pair or its exact inverse; the timestamp actually used is copied
 * onto every converted leg item for audit.
 */
@Entity
@Table(name = "fx_rate")
@IdClass(FxRateId.class)
public class FxRate {

    @Id
    @Column(name = "base_ccy", length = 3)
    private String baseCurrency;

    @Id
    @Column(name = "quote_ccy", length = 3)
    private String quoteCurrency;

    @Id
    @Column(name = "as_of")
    private OffsetDateTime asOf;

    @Column(nullable = false, precision = 20, scale = 10)
    private BigDecimal rate;

    @Column(length = 32)
    private String source;

    protected FxRate() {
    }

    public FxRate(String baseCurrency, String quoteCurrency, OffsetDateTime asOf, BigDecimal rate, String source) {
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.asOf = asOf;
        this.rate = rate;
        this.source = source;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public String getQuoteCurrency() {
        return quoteCurrency;
    }

    public OffsetDateTime getAsOf() {
        return asOf;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public String getSource() {
        return source;
    }
}
