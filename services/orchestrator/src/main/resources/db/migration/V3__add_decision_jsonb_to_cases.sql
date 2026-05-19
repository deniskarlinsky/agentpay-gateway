-- AgentPay Orchestrator schema, Iter 4b.3 (FR-O-005, Scenario C). Adds JSONB persistence for the
-- two payloads the Saga needs to survive a SUSPENDED_FOR_REVIEW pause across restarts: the
-- aggregated Decision produced by the supervisor at REVIEW time, and the agentMetadata that
-- arrived with the original PaymentContext. These replace the in-memory ConcurrentHashMap caches
-- in PaymentSaga (deferred from Iter 4b.2; resolves tech debt #1).
--
-- Choice of JSONB on `cases` (vs. separate tables): both fields are an aggregate read at resume
-- time — a single SELECT, no normalized queries. Splitting them into side tables would only add
-- joins to the recovery path without a payoff. See ADR-010 for the broader outbox rationale.

ALTER TABLE cases ADD COLUMN decision_jsonb        JSONB;
ALTER TABLE cases ADD COLUMN agent_metadata_jsonb  JSONB;
