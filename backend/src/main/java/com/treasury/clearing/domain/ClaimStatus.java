package com.treasury.clearing.domain;

/**
 * Lifecycle of an original receivable.
 * Only OPEN claims are eligible for netting; PLEDGED and DISPUTED are always excluded.
 */
public enum ClaimStatus {
    OPEN,
    PLEDGED,
    DISPUTED,
    SETTLED
}
