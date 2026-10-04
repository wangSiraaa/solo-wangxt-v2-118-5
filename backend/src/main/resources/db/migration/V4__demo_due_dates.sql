-- ============================================================================
-- Reshape demo due dates around the 2026-09-30 valuation date so the optional
-- maturity filter is demonstrable on the seeded dataset:
--   * ring 1 (NA-CNY)  : all three invoices are already due;
--   * ring 2 (NA-CNY)  : two invoices are not yet due, one has no due date
--                        (kept under the existing "no due date" rule);
--   * NA-XCCY          : only the CNY claim CLM-XC-001 is due at valuation;
--   * pledged/disputed : left late on purpose — status exclusion must win;
--   * NA-NOFF          : untouched: a set-off-forbidding agreement always
--                        preserves its original debts, the filter or not.
-- ============================================================================

-- Ring 1: matured at 2026-09-30.
UPDATE claim SET due_date = DATE '2026-09-20' WHERE id = 'CLM-R1-001';
UPDATE claim SET due_date = DATE '2026-09-25' WHERE id = 'CLM-R1-002';
UPDATE claim SET due_date = DATE '2026-09-30' WHERE id = 'CLM-R1-003';

-- Ring 2: one matured, one still open at valuation, one without a due date.
UPDATE claim SET due_date = DATE '2026-09-28' WHERE id = 'CLM-R2-001';
UPDATE claim SET due_date = DATE '2026-10-18' WHERE id = 'CLM-R2-002';
UPDATE claim SET due_date = NULL          WHERE id = 'CLM-R2-003';

-- Status-excluded claims mature already: PLEDGED/DISPUTED must still win.
UPDATE claim SET due_date = DATE '2026-09-20' WHERE id = 'CLM-EX-001';
UPDATE claim SET due_date = DATE '2026-09-21' WHERE id = 'CLM-EX-002';

-- Cross-currency pocket: only the CNY receivable is due at valuation.
UPDATE claim SET due_date = DATE '2026-09-28' WHERE id = 'CLM-XC-001';
-- CLM-XC-002 (USD) stays 2026-10-15 -> deferred when the filter is on.
-- CLM-XC-003 (CNY) stays 2026-10-16 -> deferred when the filter is on.
-- CLM-XC-004 (EUR) stays 2026-10-17 -> CCY_NOT_ALLOWED still wins.
