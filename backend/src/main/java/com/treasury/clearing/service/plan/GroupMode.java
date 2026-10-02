package com.treasury.clearing.service.plan;

/** How a pocket is settled. */
public enum GroupMode {
    /** Set-off forbidden: one leg per original claim, original currency, no FX. */
    PASS_THROUGH,
    /** Netting allowed, single currency, rate 1. */
    NET_SAME_CURRENCY,
    /** Netting across currencies, every non-settlement item is converted. */
    NET_CROSS_CURRENCY
}
