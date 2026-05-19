# ADR-010: Use Transactional Outbox for Atomic State + Event Publication

**Status:** Accepted
**Date:** 2026-05-19

## Context
The orchestrator must publish state-transition events atomically with the saga state change that produced them (`NFR-R-003`). Losing a `payment.completed` after writing `COMMITTED` to Postgres — or vice versa — is unacceptable. Spring Kafka 3.x removed `ChainedKafkaTransactionManager`, the legacy mechanism for joining a JPA tx and a Kafka tx, forcing a revisit. Iter 4b.3 / Scenario C extends the same requirement to a non-terminal transition: entering `SUSPENDED_FOR_REVIEW` (`FR-O-005`) must emit a `HumanApprovalRequest` event atomically with the state change.

## Decision
We use the transactional outbox pattern. Every state transition writes (a) the `cases` row, (b) the `saga_transitions` audit row, and (c) — when an event must be emitted — one `event_outbox` row inside a single Spring `@Transactional` JPA boundary. A separate `@Scheduled` `OutboxPublisher` polls rows with `published_at IS NULL`, sends them via a transactional Kafka producer (`spring.kafka.producer.transaction-id-prefix=orchestrator-tx-`), and marks them published on success. Consumers are idempotent, keyed by `case_id`.

## Consequences
- Atomicity is real: a transition either persists with its outbox row or rolls back entirely.
- Publication latency ~50ms from `agentpay.outbox.poll-interval-ms` (default 200ms, 50ms in tests). Acceptable for a payment-decision plane.
- Single source of truth for "what should be published": the `event_outbox` table — debuggable, replayable.
- `cases.decision_jsonb` + `cases.agent_metadata_jsonb` (Iter 4b.3) ride on the same boundary: JSONB persistence + outbox row + state transition are one tx.
- Discipline required: every transition that emits Kafka must write its outbox row in the same `@Transactional` method as the state change. Easy to forget; enforced by code review and Scenario C tests.
- At-least-once delivery — downstream consumers must be idempotent. Already the case.

## Alternatives considered
- **`ChainedKafkaTransactionManager`** — removed in Spring Kafka 3.x. Not available on the pinned stack. Original reason for revisiting.
- **Fire-and-forget Kafka send inside the JPA `@Transactional`** — the default reviewer instinct. Rejected: the send completes outside the JPA commit phase, so a broker outage or post-send JPA rollback breaks atomicity. Lost-message risk.
- **Two-phase commit (XA across Postgres + Kafka)** — rejected: operationally fragile, requires an XA-capable Kafka client, not idiomatic with Spring Boot 3.5.

## When to revisit
When publication latency under 50ms becomes a requirement, or when Spring Kafka ships a supported atomic JPA+Kafka coordinator on the pinned stack.

Related: [[002-saga-coordinator-vs-state-machine]], [[006-virtual-threads-no-preview]].
