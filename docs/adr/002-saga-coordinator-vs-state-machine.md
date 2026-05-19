# ADR-002: Explicit @Service Saga Coordinator, Not Spring State Machine

**Status:** Accepted
**Date:** 2026-05-19

## Context
The Orchestrator owns one Saga with nine states per `FR-O-001`: `INITIATED`, `HELD`, `REVIEWING`, `APPROVED`, `ROUTED`, `COMMITTED`, `DECLINED`, `COMPENSATED`, `SUSPENDED_FOR_REVIEW`. Transitions are linear with two branch points (Decision outcome at `REVIEWING`; PSP success/failure at `ROUTED`). Compensation is one path. The common Spring instinct here is Spring State Machine — DSL config, persisted contexts, event listeners. For a single saga of nine states that's more framework than the problem warrants, and the framework hides exactly the thing a reviewer of this repo wants to read: the transition logic.

## Decision
We implement the Saga as a single `@Service` class, `PaymentSaga.java`, with one `@Transactional` method per state-changing operation (`start`, `onDecision`, `onApprovalGranted`, `onPspResult`, etc.). Every transition is plain Java in one file. State is a single `SagaState` enum column on `cases`. No DSL, no listener model, no separate persistence layer for machine context.

## Consequences
- Every transition is greppable and steps through under a debugger without crossing a framework boundary. Onboarding a reviewer means reading one file.
- Each `@Transactional` method is also the atomic boundary for the outbox row that the transition emits — co-locating the state change and the event write is trivial. See [[010-transactional-outbox-vs-chained-kafka-tx-manager]].
- Self-injection via `private PaymentSaga self` is required so internal calls go through the AOP proxy and `@Transactional` actually applies. This is a known Spring quirk, documented in the class Javadoc.
- We lose the freebies State Machine would have given us: visual diagrams, declarative guards/actions, persisted machine context. None of these are in `REQUIREMENTS.md`.
- The pattern does not scale to ten parallel sagas. If a second one appears, the case for a framework grows.

## Alternatives considered
- **Spring State Machine.** The reviewer-default option for "a state machine in Spring." Rejected: it introduces a DSL plus a persistence layer plus a listener model to express what is, in this project, a linear nine-state walk with two branches. The framework would obscure the very flow this repo is trying to demonstrate.
- **A hand-rolled `Map<State, Function<Event, State>>` transition table.** Rejected: more clever than clear; harder to grep than the imperative version.

## When to revisit
When a second Saga (e.g., chargeback-evidence collection, or a multi-leg refund) appears in scope. Two coordinators sharing zero infrastructure is the point at which Spring State Machine — or at least a shared transition abstraction — earns its weight.

Related: [[010-transactional-outbox-vs-chained-kafka-tx-manager]].
