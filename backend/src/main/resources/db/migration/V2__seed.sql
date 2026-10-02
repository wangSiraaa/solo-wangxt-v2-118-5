-- ============================================================================
-- Demo dataset for the 2026-09-30 valuation date.
--
-- NA-CNY   : three-party CNY rings, netting allowed, same currency
-- NA-NOFF  : set-off prohibited by agreement -> original debts preserved
-- NA-XCCY  : cross-currency netting into CNY, with a fixed FX snapshot time
-- ============================================================================

INSERT INTO legal_entity (code, name, functional_currency, active, created_at) VALUES
 ('A', '甲公司', 'CNY', TRUE, DATE '2026-01-01'),
 ('B', '乙公司', 'CNY', TRUE, DATE '2026-01-01'),
 ('C', '丙公司', 'CNY', TRUE, DATE '2026-01-01'),
 ('D', '丁公司', 'CNY', TRUE, DATE '2026-01-01'),
 ('E', '戊公司', 'USD', TRUE, DATE '2026-01-01');

-- --- NA-CNY: multilateral netting in CNY ------------------------------------
INSERT INTO netting_agreement
 (code, name, allows_netting, cross_currency, settlement_ccy, rounding_bearer_code,
  effective_from, effective_to, fx_as_of, fx_margin)
VALUES
 ('NA-CNY', '境内三方抵销协议', TRUE, FALSE, 'CNY', 'A',
  DATE '2026-01-01', NULL, NULL, NULL);

INSERT INTO agreement_member (agreement_code, entity_code) VALUES
 ('NA-CNY','A'), ('NA-CNY','B'), ('NA-CNY','C'), ('NA-CNY','D');
INSERT INTO agreement_currency (agreement_code, currency) VALUES
 ('NA-CNY','CNY');

-- Closed ring: A->B 1000, B->C 600, C->A 400.
-- Net positions: A +600, B +400, C -1000  -> 2 real legs, 3 debts cancelled
INSERT INTO claim
 (id, invoice_no, agreement_code, debtor_code, creditor_code, amount, currency,
  status, invoice_date, due_date, description, created_at)
VALUES
 ('CLM-R1-001','INV-A-1001','NA-CNY','A','B',1000.00,'CNY','OPEN',
  DATE '2026-09-10', DATE '2026-10-10','甲采购乙服务费', TIMESTAMP '2026-09-10 00:00:00 UTC'),
 ('CLM-R1-002','INV-B-2001','NA-CNY','B','C',600.00,'CNY','OPEN',
  DATE '2026-09-12', DATE '2026-10-12','乙采购丙货物',   TIMESTAMP '2026-09-12 00:00:00 UTC'),
 ('CLM-R1-003','INV-C-3001','NA-CNY','C','A',400.00,'CNY','OPEN',
  DATE '2026-09-15', DATE '2026-10-15','丙采购甲软件',   TIMESTAMP '2026-09-15 00:00:00 UTC');

-- A second closed ring (200 each) -> 3 more debts, zero cash.
INSERT INTO claim
 (id, invoice_no, agreement_code, debtor_code, creditor_code, amount, currency,
  status, invoice_date, due_date, description, created_at)
VALUES
 ('CLM-R2-001','INV-A-1002','NA-CNY','A','B',200.00,'CNY','OPEN',
  DATE '2026-09-16', DATE '2026-10-16','甲采购乙备件', TIMESTAMP '2026-09-16 00:00:00 UTC'),
 ('CLM-R2-002','INV-B-2002','NA-CNY','B','C',200.00,'CNY','OPEN',
  DATE '2026-09-17', DATE '2026-10-17','乙采购丙备件', TIMESTAMP '2026-09-17 00:00:00 UTC'),
 ('CLM-R2-003','INV-C-3002','NA-CNY','C','A',200.00,'CNY','OPEN',
  DATE '2026-09-18', DATE '2026-10-18','丙采购甲维护', TIMESTAMP '2026-09-18 00:00:00 UTC');

-- Excluded claims: pledged and disputed must never enter netting.
INSERT INTO claim
 (id, invoice_no, agreement_code, debtor_code, creditor_code, amount, currency,
  status, invoice_date, due_date, description, created_at)
VALUES
 ('CLM-EX-001','INV-A-1090','NA-CNY','A','C',300.00,'CNY','PLEDGED',
  DATE '2026-09-20', DATE '2026-10-20','甲对丙债权已质押给银行', TIMESTAMP '2026-09-20 00:00:00 UTC'),
 ('CLM-EX-002','INV-B-2090','NA-CNY','B','A',150.27,'CNY','DISPUTED',
  DATE '2026-09-21', DATE '2026-10-21','乙对甲债权存在争议', TIMESTAMP '2026-09-21 00:00:00 UTC');

-- --- NA-NOFF: agreement that forbids set-off -------------------------------
INSERT INTO netting_agreement
 (code, name, allows_netting, cross_currency, settlement_ccy, rounding_bearer_code,
  effective_from, effective_to, fx_as_of, fx_margin)
VALUES
 ('NA-NOFF', '禁止抵销双边协议', FALSE, FALSE, 'CNY', NULL,
  DATE '2026-01-01', NULL, NULL, NULL);

INSERT INTO agreement_member (agreement_code, entity_code) VALUES
 ('NA-NOFF','A'), ('NA-NOFF','B'), ('NA-NOFF','C');
INSERT INTO agreement_currency (agreement_code, currency) VALUES
 ('NA-NOFF','CNY');

-- Even a perfectly closed ring stays as 3 payments under this agreement.
INSERT INTO claim
 (id, invoice_no, agreement_code, debtor_code, creditor_code, amount, currency,
  status, invoice_date, due_date, description, created_at)
VALUES
 ('CLM-NF-001','INV-NA-5001','NA-NOFF','A','B',500.00,'CNY','OPEN',
  DATE '2026-09-10', DATE '2026-10-10','禁止抵销协议下甲欠乙', TIMESTAMP '2026-09-10 00:00:00 UTC'),
 ('CLM-NF-002','INV-NB-6001','NA-NOFF','B','C',500.00,'CNY','OPEN',
  DATE '2026-09-11', DATE '2026-10-11','禁止抵销协议下乙欠丙', TIMESTAMP '2026-09-11 00:00:00 UTC'),
 ('CLM-NF-003','INV-NC-7001','NA-NOFF','C','A',500.00,'CNY','OPEN',
  DATE '2026-09-12', DATE '2026-10-12','禁止抵销协议下丙欠甲', TIMESTAMP '2026-09-12 00:00:00 UTC');

-- --- NA-XCCY: cross-currency netting, settlement CNY -----------------------
INSERT INTO netting_agreement
 (code, name, allows_netting, cross_currency, settlement_ccy, rounding_bearer_code,
  effective_from, effective_to, fx_as_of, fx_margin)
VALUES
 ('NA-XCCY', '跨币种抵销协议(结算CNY)', TRUE, TRUE, 'CNY', 'A',
  DATE '2026-01-01', NULL, TIMESTAMP '2026-09-30 09:00:00 UTC', NULL);

INSERT INTO agreement_member (agreement_code, entity_code) VALUES
 ('NA-XCCY','A'), ('NA-XCCY','B'), ('NA-XCCY','E');
INSERT INTO agreement_currency (agreement_code, currency) VALUES
 ('NA-XCCY','CNY'), ('NA-XCCY','USD');

-- USD->CNY snapshots. The agreement's fx_as_of is 2026-09-30 09:00 UTC, so the
-- 08:30 quote is the point-in-time rate actually used; 16:30 is newer but ignored.
INSERT INTO fx_rate (base_ccy, quote_ccy, as_of, rate, source) VALUES
 ('USD','CNY', TIMESTAMP '2026-09-29 16:30:00 UTC', 7.2100000000, 'TREASURY-CLOSE'),
 ('USD','CNY', TIMESTAMP '2026-09-30 08:30:00 UTC', 7.2053500000, 'TREASURY-INTRADAY'),
 ('USD','CNY', TIMESTAMP '2026-09-30 16:30:00 UTC', 7.1980000000, 'TREASURY-CLOSE');

-- Ring in mixed currencies. USD 100.00 * 7.20535 = 720.535 exact -> 720.54 booked (+0.005 diff).
INSERT INTO claim
 (id, invoice_no, agreement_code, debtor_code, creditor_code, amount, currency,
  status, invoice_date, due_date, description, created_at)
VALUES
 ('CLM-XC-001','INV-XA-8001','NA-XCCY','A','E',1000.00,'CNY','OPEN',
  DATE '2026-09-14', DATE '2026-10-14','甲欠戊(人民币)', TIMESTAMP '2026-09-14 00:00:00 UTC'),
 ('CLM-XC-002','INV-XE-9001','NA-XCCY','E','B',100.00,'USD','OPEN',
  DATE '2026-09-15', DATE '2026-10-15','戊欠乙(美元发票)', TIMESTAMP '2026-09-15 00:00:00 UTC'),
 ('CLM-XC-003','INV-XB-8002','NA-XCCY','B','A',500.00,'CNY','OPEN',
  DATE '2026-09-16', DATE '2026-10-16','乙欠甲(人民币)', TIMESTAMP '2026-09-16 00:00:00 UTC'),
-- EUR claim is outside the agreement currency scope -> CCY_NOT_ALLOWED
 ('CLM-XC-004','INV-XE-9002','NA-XCCY','E','A',70.00,'EUR','OPEN',
  DATE '2026-09-17', DATE '2026-10-17','戊对甲欧元债权(币种超范围)', TIMESTAMP '2026-09-17 00:00:00 UTC');
