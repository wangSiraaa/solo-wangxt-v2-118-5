package com.treasury.clearing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * A netting / set-off agreement that defines the protocol boundary:
 * which entities may offset against each other, in which currencies,
 * and whether netting (and cross-currency set-off) is allowed at all.
 */
@Entity
@Table(name = "netting_agreement")
public class NettingAgreement {

    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** false = set-off prohibited by the agreement: original debts must be preserved as-is. */
    @Column(name = "allows_netting", nullable = false)
    private boolean allowsNetting = true;

    /** Cross-currency set-off requires explicit permission; default off. */
    @Column(name = "cross_currency", nullable = false)
    private boolean crossCurrency = false;

    /** Settlement currency for the (single) group produced from this agreement. */
    @Column(name = "settlement_ccy", length = 3)
    private String settlementCurrency;

    /**
     * Legal entity that absorbs the rounding remainder of cross-currency legs so that
     * the sum of legs is zero in settlement currency after rounding to cents.
     */
    @Column(name = "rounding_bearer_code", length = 32)
    private String roundingBearerCode;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** FX rate snapshot timestamp used for the trial; null = latest available rate. */
    @Column(name = "fx_as_of")
    private OffsetDateTime fxAsOf;

    @Column(precision = 20, scale = 10)
    private BigDecimal fxMargin;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agreement_member", joinColumns = @JoinColumn(name = "agreement_code"))
    @Column(name = "entity_code", length = 32)
    private Set<String> members = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agreement_currency", joinColumns = @JoinColumn(name = "agreement_code"))
    @Column(name = "currency", length = 3)
    private Set<String> currencies = new HashSet<>();

    protected NettingAgreement() {
    }

    public NettingAgreement(String code, String name, boolean allowsNetting, boolean crossCurrency,
                            String settlementCurrency, String roundingBearerCode,
                            LocalDate effectiveFrom, LocalDate effectiveTo,
                            OffsetDateTime fxAsOf, BigDecimal fxMargin) {
        this.code = code;
        this.name = name;
        this.allowsNetting = allowsNetting;
        this.crossCurrency = crossCurrency;
        this.settlementCurrency = settlementCurrency;
        this.roundingBearerCode = roundingBearerCode;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.fxAsOf = fxAsOf;
        this.fxMargin = fxMargin;
    }

    public void addMember(String entityCode) {
        members.add(entityCode);
    }

    public void addCurrency(String currency) {
        currencies.add(currency);
    }

    public boolean hasMember(String entityCode) {
        return members.contains(entityCode);
    }

    public boolean allowsCurrency(String currency) {
        return currencies.isEmpty() || currencies.contains(currency);
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isAllowsNetting() {
        return allowsNetting;
    }

    public boolean isCrossCurrency() {
        return crossCurrency;
    }

    public String getSettlementCurrency() {
        return settlementCurrency;
    }

    public String getRoundingBearerCode() {
        return roundingBearerCode;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public OffsetDateTime getFxAsOf() {
        return fxAsOf;
    }

    public BigDecimal getFxMargin() {
        return fxMargin;
    }

    public Set<String> getMembers() {
        return members;
    }

    public Set<String> getCurrencies() {
        return currencies;
    }
}
