package com.treasury.clearing.domain;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

public class FxRateId implements Serializable {

    private String baseCurrency;
    private String quoteCurrency;
    private OffsetDateTime asOf;

    public FxRateId() {
    }

    public FxRateId(String baseCurrency, String quoteCurrency, OffsetDateTime asOf) {
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.asOf = asOf;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FxRateId that)) {
            return false;
        }
        return Objects.equals(baseCurrency, that.baseCurrency)
                && Objects.equals(quoteCurrency, that.quoteCurrency)
                && Objects.equals(asOf, that.asOf);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseCurrency, quoteCurrency, asOf);
    }
}
