package com.treasury.clearing.domain;

/** Lifecycle of a netting batch. No state transition touches a real bank. */
public enum BatchStatus {
    /** Trial plan only: nothing is settled, nothing is booked. */
    SIMULATED,
    /** Treasurer confirmed the plan; original claims carry netting references. */
    CONFIRMED,
    /** Simulated payments were "sent" — a ledger record, not a bank instruction. */
    PAID_SIMULATED
}
