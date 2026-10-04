-- ============================================================================
-- Batch-level optional maturity screen:
-- when only_due_claims = TRUE the run is limited to claims whose due date is on
-- or before the valuation date (later-due claims are excluded as NOT_DUE);
-- claims without a due date keep their existing treatment.
-- Default FALSE keeps every previously simulated batch unchanged.
-- ============================================================================

ALTER TABLE netting_batch
    ADD COLUMN only_due_claims BOOLEAN NOT NULL DEFAULT FALSE;
