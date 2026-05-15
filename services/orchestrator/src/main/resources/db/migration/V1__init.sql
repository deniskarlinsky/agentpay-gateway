-- AgentPay Orchestrator schema, Iter 3 (FR-O-001..009, NFR-R-002..003).
-- Flyway hygiene: this file is IMMUTABLE once applied. Schema evolution lands in V2, V3, ...
--
-- Tables per REQUIREMENTS.md §8, plus `event_outbox` (transactional outbox pattern, see ADR
-- candidate / B-4 in the Iter 3 plan: replaces the removed ChainedKafkaTransactionManager).

CREATE TABLE cases (
    case_id          VARCHAR(64) PRIMARY KEY,
    agent_id         VARCHAR(64) NOT NULL,
    merchant_id      VARCHAR(64) NOT NULL,
    amount           NUMERIC(18, 4) NOT NULL,
    currency         CHAR(3) NOT NULL,
    state            VARCHAR(32) NOT NULL,
    intent_token_jti UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cost_usd         NUMERIC(10, 6)
);

-- Crash-recovery scan: SagaRecoveryRunner queries non-terminal cases on startup (NFR-R-002).
-- Partial index keeps the scan cheap as terminal cases accumulate.
CREATE INDEX idx_cases_non_terminal_state
    ON cases (state)
    WHERE state NOT IN ('COMMITTED', 'DECLINED', 'COMPENSATED');

CREATE TABLE saga_transitions (
    id          BIGSERIAL PRIMARY KEY,
    case_id     VARCHAR(64) REFERENCES cases(case_id),
    state_from  VARCHAR(32),
    state_to    VARCHAR(32) NOT NULL,
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_saga_transitions_case_id
    ON saga_transitions (case_id, created_at);

CREATE TABLE agent_verdicts (
    id             BIGSERIAL PRIMARY KEY,
    case_id        VARCHAR(64) REFERENCES cases(case_id),
    agent_name     VARCHAR(64) NOT NULL,
    model          VARCHAR(64) NOT NULL,
    verdict_json   JSONB NOT NULL,
    input_tokens   INT NOT NULL,
    output_tokens  INT NOT NULL,
    cost_usd       NUMERIC(10, 6) NOT NULL,
    latency_ms     INT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- pgvector extension is already enabled by ops/postgres/init/01-init.sql.
CREATE TABLE route_metrics (
    psp_id          VARCHAR(64),
    route_id        VARCHAR(64),
    embedding       vector(1536),
    metrics_json    JSONB NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (psp_id, route_id)
);

-- Transactional outbox: state-change rows are inserted in the SAME JPA transaction as the
-- cases/saga_transitions writes, then a separate poller drains them to Kafka. This is the
-- modern replacement for the removed Spring Kafka ChainedKafkaTransactionManager (B-4).
CREATE TABLE event_outbox (
    id            BIGSERIAL PRIMARY KEY,
    case_id       VARCHAR(64) NOT NULL REFERENCES cases(case_id),
    topic         VARCHAR(128) NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    payload       BYTEA NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ
);

-- Partial index keeps the publisher poll cheap as published rows accumulate.
CREATE INDEX idx_event_outbox_unpublished
    ON event_outbox (id)
    WHERE published_at IS NULL;
