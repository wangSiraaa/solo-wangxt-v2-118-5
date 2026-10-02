-- ============================================================================
-- Intercompany netting trial — schema
-- PostgreSQL keeps netting batches and the original debts.
-- All money columns are DECIMAL (never float); all amounts are positive amounts
-- with direction carried by debtor/creditor columns.
-- ============================================================================

CREATE TABLE legal_entity (
    code                VARCHAR(32) PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    functional_currency VARCHAR(3),
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          DATE NOT NULL
);

CREATE TABLE netting_agreement (
    code                 VARCHAR(32) PRIMARY KEY,
    name                 VARCHAR(128) NOT NULL,
    allows_netting       BOOLEAN NOT NULL DEFAULT TRUE,
    cross_currency       BOOLEAN NOT NULL DEFAULT FALSE,
    settlement_ccy       VARCHAR(3),
    rounding_bearer_code VARCHAR(32),
    effective_from       DATE NOT NULL,
    effective_to         DATE,
    fx_as_of             TIMESTAMP WITH TIME ZONE,
    fx_margin            DECIMAL(20,10),
    CONSTRAINT chk_agreement_bearer CHECK (
        rounding_bearer_code IS NULL OR length(rounding_bearer_code) > 0
    )
);

CREATE TABLE agreement_member (
    agreement_code VARCHAR(32) NOT NULL
        REFERENCES netting_agreement(code),
    entity_code    VARCHAR(32) NOT NULL
        REFERENCES legal_entity(code),
    PRIMARY KEY (agreement_code, entity_code)
);

CREATE TABLE agreement_currency (
    agreement_code VARCHAR(32) NOT NULL
        REFERENCES netting_agreement(code),
    currency       VARCHAR(3) NOT NULL,
    PRIMARY KEY (agreement_code, currency)
);

CREATE TABLE claim (
    id              VARCHAR(40) PRIMARY KEY,
    invoice_no      VARCHAR(64),
    agreement_code  VARCHAR(32) NOT NULL
        REFERENCES netting_agreement(code),
    debtor_code     VARCHAR(32) NOT NULL
        REFERENCES legal_entity(code),
    creditor_code   VARCHAR(32) NOT NULL
        REFERENCES legal_entity(code),
    amount          DECIMAL(20,2) NOT NULL CHECK (amount >= 0),
    currency        VARCHAR(3) NOT NULL,
    status          VARCHAR(16) NOT NULL CHECK (status IN ('OPEN','PLEDGED','DISPUTED','SETTLED')),
    invoice_date    DATE NOT NULL,
    due_date        DATE,
    description     VARCHAR(256),
    offset_batch_id VARCHAR(40),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_claim_parties CHECK (debtor_code <> creditor_code)
);

CREATE INDEX idx_claim_agreement ON claim(agreement_code);
CREATE INDEX idx_claim_status ON claim(status);
CREATE INDEX idx_claim_offset_batch ON claim(offset_batch_id);

CREATE TABLE fx_rate (
    base_ccy  VARCHAR(3) NOT NULL,
    quote_ccy VARCHAR(3) NOT NULL,
    as_of     TIMESTAMP WITH TIME ZONE NOT NULL,
    rate      DECIMAL(20,10) NOT NULL,
    source    VARCHAR(32),
    PRIMARY KEY (base_ccy, quote_ccy, as_of),
    CONSTRAINT chk_fx_pair CHECK (base_ccy <> quote_ccy),
    CONSTRAINT chk_fx_rate CHECK (rate > 0)
);

CREATE TABLE netting_batch (
    id                     VARCHAR(40) PRIMARY KEY,
    valuation_date         DATE NOT NULL,
    status                 VARCHAR(20) NOT NULL
                           CHECK (status IN ('SIMULATED','CONFIRMED','PAID_SIMULATED')),
    incoming_claim_count   INTEGER NOT NULL,
    included_claim_count   INTEGER NOT NULL,
    excluded_claim_count   INTEGER NOT NULL,
    original_leg_count     INTEGER NOT NULL,
    netted_leg_count       INTEGER NOT NULL,
    gross_amount           DECIMAL(20,2),
    net_amount             DECIMAL(20,2),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at           TIMESTAMP WITH TIME ZONE,
    paid_simulated_at      TIMESTAMP WITH TIME ZONE,
    note                   VARCHAR(256)
);

CREATE TABLE batch_group (
    id                    VARCHAR(40) PRIMARY KEY,
    batch_id              VARCHAR(40) NOT NULL
                          REFERENCES netting_batch(id),
    agreement_code        VARCHAR(32) NOT NULL,
    agreement_name        VARCHAR(128),
    settlement_ccy        VARCHAR(3) NOT NULL,
    cross_currency        BOOLEAN NOT NULL,
    pass_through          BOOLEAN NOT NULL,
    original_leg_count    INTEGER NOT NULL,
    netted_leg_count      INTEGER NOT NULL,
    gross_amount          DECIMAL(20,2) NOT NULL,
    net_amount            DECIMAL(20,2) NOT NULL,
    rounding_diff_total   DECIMAL(20,6) NOT NULL,
    rounding_residual     DECIMAL(20,2) NOT NULL,
    rounding_bearer_code  VARCHAR(32)
);

CREATE INDEX idx_batch_group_batch ON batch_group(batch_id);

CREATE TABLE batch_leg (
    id                   VARCHAR(40) PRIMARY KEY,
    group_id             VARCHAR(40) NOT NULL
                         REFERENCES batch_group(id),
    payer_code           VARCHAR(32) NOT NULL,
    receiver_code        VARCHAR(32) NOT NULL,
    amount               DECIMAL(20,2) NOT NULL,
    settlement_ccy       VARCHAR(3) NOT NULL,
    is_original          BOOLEAN NOT NULL,
    amount_exact         DECIMAL(20,6),
    residual_adjustment  DECIMAL(20,2) NOT NULL DEFAULT 0,
    seq_no               INTEGER NOT NULL,
    paid_simulated_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_batch_leg_group ON batch_leg(group_id);

CREATE TABLE leg_item (
    id                    VARCHAR(40) PRIMARY KEY,
    leg_id                VARCHAR(40) NOT NULL
                          REFERENCES batch_leg(id),
    claim_id              VARCHAR(40) NOT NULL,
    invoice_no            VARCHAR(64),
    debtor_code           VARCHAR(32) NOT NULL,
    creditor_code         VARCHAR(32) NOT NULL,
    side                  VARCHAR(8) NOT NULL CHECK (side IN ('PAYER','RECEIVER')),
    original_amount       DECIMAL(20,2) NOT NULL,
    original_ccy          VARCHAR(3) NOT NULL,
    fx_rate               DECIMAL(20,10),
    fx_as_of              TIMESTAMP WITH TIME ZONE,
    fx_source             VARCHAR(32),
    fx_inverted           BOOLEAN NOT NULL DEFAULT FALSE,
    converted_exact       DECIMAL(20,6) NOT NULL,
    converted_booked      DECIMAL(20,2) NOT NULL,
    rounding_diff         DECIMAL(20,6) NOT NULL,
    rounding_bearer_code  VARCHAR(32)
);

CREATE INDEX idx_leg_item_leg ON leg_item(leg_id);
CREATE INDEX idx_leg_item_claim ON leg_item(claim_id);

CREATE TABLE batch_exclusion (
    id            VARCHAR(40) PRIMARY KEY,
    group_id      VARCHAR(40) NOT NULL
                  REFERENCES batch_group(id),
    claim_id      VARCHAR(40) NOT NULL,
    invoice_no    VARCHAR(64),
    reason_code   VARCHAR(32) NOT NULL,
    reason_detail VARCHAR(256)
);

CREATE INDEX idx_batch_exclusion_group ON batch_exclusion(group_id);
