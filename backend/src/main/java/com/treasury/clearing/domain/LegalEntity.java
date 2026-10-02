package com.treasury.clearing.domain;

import jakarta.persistence.*;

import java.time.LocalDate;

/** A legal entity inside the group (甲, 乙, 丙, ...). */
@Entity
@Table(name = "legal_entity")
public class LegalEntity {

    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 3)
    private String functionalCurrency;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDate createdAt = LocalDate.of(2026, 9, 30);

    protected LegalEntity() {
    }

    public LegalEntity(String code, String name, String functionalCurrency) {
        this.code = code;
        this.name = name;
        this.functionalCurrency = functionalCurrency;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getFunctionalCurrency() {
        return functionalCurrency;
    }

    public boolean isActive() {
        return active;
    }
}
