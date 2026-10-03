-- Customers double as login principals.
CREATE TABLE customers (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Customer accounts and the bank's own internal (GL) accounts live in the same table.
-- balance is a denormalised cache of SUM(ledger_entries); reconciliation verifies they agree.
CREATE TABLE accounts (
    id             BIGSERIAL PRIMARY KEY,
    customer_id    BIGINT REFERENCES customers (id),
    account_number VARCHAR(34)    NOT NULL UNIQUE,
    type           VARCHAR(20)    NOT NULL,
    internal_code  VARCHAR(40) UNIQUE,
    currency       VARCHAR(4)     NOT NULL,
    balance        NUMERIC(38, 8) NOT NULL DEFAULT 0,
    status         VARCHAR(20)    NOT NULL,
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT customer_accounts_have_owner CHECK (type <> 'CUSTOMER' OR customer_id IS NOT NULL),
    CONSTRAINT customer_balance_non_negative CHECK (type <> 'CUSTOMER' OR balance >= 0)
);
CREATE INDEX idx_accounts_customer ON accounts (customer_id);

CREATE TABLE transactions (
    id                UUID PRIMARY KEY,
    type              VARCHAR(20)    NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    from_account_id   BIGINT REFERENCES accounts (id),
    to_account_id     BIGINT REFERENCES accounts (id),
    amount            NUMERIC(38, 8) NOT NULL,
    currency          VARCHAR(4)     NOT NULL,
    counter_amount    NUMERIC(38, 8),
    counter_currency  VARCHAR(4),
    fx_rate           NUMERIC(38, 12),
    quote_id          UUID UNIQUE,
    external_iban     VARCHAR(34),
    description       VARCHAR(255),
    initiated_by      BIGINT REFERENCES customers (id),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);
CREATE INDEX idx_tx_from ON transactions (from_account_id, created_at DESC);
CREATE INDEX idx_tx_to ON transactions (to_account_id, created_at DESC);

-- Double-entry ledger: every transaction's entries net to zero per currency.
CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID           NOT NULL REFERENCES transactions (id),
    account_id     BIGINT         NOT NULL REFERENCES accounts (id),
    direction      VARCHAR(6)     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         NUMERIC(38, 8) NOT NULL CHECK (amount > 0),
    currency       VARCHAR(4)     NOT NULL,
    balance_after  NUMERIC(38, 8) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_account ON ledger_entries (account_id, id);
CREATE INDEX idx_ledger_tx ON ledger_entries (transaction_id);

-- Idempotency keys are scoped per customer. The PK is what serialises duplicate requests.
CREATE TABLE idempotency_records (
    customer_id     BIGINT       NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    resource_id     UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, idempotency_key)
);

-- Transactional outbox.
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000)
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- Orchestrated EFT saga state.
CREATE TABLE eft_sagas (
    id             UUID PRIMARY KEY,
    transaction_id UUID           NOT NULL REFERENCES transactions (id),
    account_id     BIGINT         NOT NULL REFERENCES accounts (id),
    amount         NUMERIC(38, 8) NOT NULL,
    currency       VARCHAR(4)     NOT NULL,
    target_iban    VARCHAR(34)    NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    attempts       INT            NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_saga_status ON eft_sagas (status, updated_at);

CREATE TABLE fraud_alerts (
    id             BIGSERIAL PRIMARY KEY,
    account_id     BIGINT       NOT NULL REFERENCES accounts (id),
    transaction_id UUID,
    rule           VARCHAR(50)  NOT NULL,
    action         VARCHAR(20)  NOT NULL,
    details        VARCHAR(1000),
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ
);
CREATE INDEX idx_fraud_account ON fraud_alerts (account_id);

-- Consumer-side de-duplication for at-least-once delivery.
CREATE TABLE processed_events (
    event_id     UUID        NOT NULL,
    consumer     VARCHAR(50) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
