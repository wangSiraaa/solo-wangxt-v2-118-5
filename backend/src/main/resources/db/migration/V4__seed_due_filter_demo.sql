-- ============================================================================
-- Demo data for the optional maturity screen (valuation date 2026-09-30).
--
-- NA-DUE  : netting allowed, same currency. The three open invoices cover every
--           branch of the "only matured claims" option:
--             CLM-DUE-001  due 2026-09-30 -> matured on the valuation date
--             CLM-DUE-002  due 2026-10-20 -> not yet due (deferred, NOT_DUE)
--             CLM-DUE-003  no due date    -> existing rule (included as before)
-- ============================================================================

INSERT INTO netting_agreement
 (code, name, allows_netting, cross_currency, settlement_ccy, rounding_bearer_code,
  effective_from, effective_to, fx_as_of, fx_margin)
VALUES
 ('NA-DUE', '到期筛选演示协议', TRUE, FALSE, 'CNY', 'A',
  DATE '2026-01-01', NULL, NULL, NULL);

INSERT INTO agreement_member (agreement_code, entity_code) VALUES
 ('NA-DUE','A'), ('NA-DUE','B');
INSERT INTO agreement_currency (agreement_code, currency) VALUES
 ('NA-DUE','CNY');

INSERT INTO claim
 (id, invoice_no, agreement_code, debtor_code, creditor_code, amount, currency,
  status, invoice_date, due_date, description, created_at)
VALUES
 ('CLM-DUE-001','INV-DA-1001','NA-DUE','A','B',300.00,'CNY','OPEN',
  DATE '2026-09-10', DATE '2026-09-30','甲欠乙(估值日当天到期)', TIMESTAMP '2026-09-10 00:00:00 UTC'),
 ('CLM-DUE-002','INV-DB-2001','NA-DUE','B','A',200.00,'CNY','OPEN',
  DATE '2026-09-20', DATE '2026-10-20','乙欠甲(估值日后到期,被推迟)', TIMESTAMP '2026-09-20 00:00:00 UTC'),
 ('CLM-DUE-003','INV-DA-1002','NA-DUE','A','B',100.00,'CNY','OPEN',
  DATE '2026-09-25', NULL,'甲欠乙(未登记到期日,按原规则纳入)', TIMESTAMP '2026-09-25 00:00:00 UTC');
