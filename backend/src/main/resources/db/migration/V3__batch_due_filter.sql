-- ============================================================================
-- Optional per-batch maturity filter.
-- When TRUE the trial only offsets claims already due on the batch valuation
-- date: claims with due_date AFTER the valuation date land in batch_exclusion
-- (NOT_DUE_AT_VALUATION); claims without a due_date keep their existing rule.
-- FALSE (default) preserves the original trial behaviour byte-for-byte.
-- The choice is stored on the batch so old batches never change when a new
-- trial is run with a different setting (or a later valuation date).
-- ============================================================================

ALTER TABLE netting_batch
    ADD COLUMN due_filter_enabled BOOLEAN NOT NULL DEFAULT FALSE;
