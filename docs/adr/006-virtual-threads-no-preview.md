# ADR-006: Virtual Threads + CompletableFuture for Specialist Fan-Out — No StructuredTaskScope

**Status:** Accepted
**Date:** 2026-05-19

## Context
FR-DP-001 requires the Supervisor to fan out a `PaymentContext` to the three specialist agents in parallel using virtual threads via `Executors.newVirtualThreadPerTaskExecutor()` and `CompletableFuture.allOf(...)`. FR-DP-003 requires a 10-second per-call timeout. NFR-COST-001 requires a per-case cost circuit breaker that cancels remaining work when the budget is exceeded. Java 21 LTS made virtual threads GA in September 2023, but `StructuredTaskScope` — the API that would naturally model "scatter-gather with scope cancellation" — remained a preview API through Java 25. [ADR-001](001-stable-stack-baseline.md) and CLAUDE.md §4 forbid preview features.

## Decision
The Supervisor (`services/orchestrator/src/main/java/com/agentpay/orchestrator/decision/Supervisor.java`) owns one `Executors.newVirtualThreadPerTaskExecutor()`, wraps it with `ContextSnapshotFactory.captureAll().wrapExecutor(...)` for Micrometer Observation propagation, schedules each specialist via `CompletableFuture.supplyAsync`, applies `.orTimeout(10, SECONDS)`, and joins with `CompletableFuture.allOf`. The budget circuit breaker uses each future's `whenComplete` to read the running cost from `CostTracker`; when the budget is breached, sibling futures are `cancel(true)`'d and a `Decision.budgetReview` is returned.

## Consequences
- ~10 lines longer than the equivalent `StructuredTaskScope` body. Three near-identical `supplyAsync(...).orTimeout(...).exceptionally(...)` blocks instead of one scope. Acceptable.
- No scope-wide cancellation primitive: we cancel by iterating siblings in `whenComplete`. `CompletableFuture.cancel(true)` does **not** interrupt the `supplyAsync` worker — the in-flight Anthropic call may complete in the background; we just stop waiting. Documented in the Supervisor Javadoc.
- The executor is shut down via `@PreDestroy` with a 5s grace period (CLAUDE.md §8 gotcha). No thread-leak risk in tests.
- Per-future `orTimeout(10, SECONDS)` cleanly satisfies FR-DP-003 with no extra scheduler.
- Context propagation across `supplyAsync` needs explicit `ContextSnapshot.wrapExecutor` — virtual threads do not carry Observation context for free (FR-DP-004). This is captured in code comments at the use site.

## Alternatives considered
- **`StructuredTaskScope`** — the reasonable default for fan-out + cancellation in modern Java. Rejected: preview through Java 25, violates [ADR-001](001-stable-stack-baseline.md)'s no-preview rule. Re-evaluable on GA.
- **Bounded platform-thread pool** (`Executors.newFixedThreadPool(3)`). Rejected: the workload — three concurrent blocking LLM HTTP calls per case — is exactly what virtual threads were designed for; platform threads would impose an arbitrary parallelism ceiling under load with no offsetting benefit on the pinned stack.
- **Reactive `Flux.merge`**. Rejected: FR-DP-001 and CLAUDE.md §9 forbid reactive Spring unless a requirement demands it; none does.

## When to revisit
When `StructuredTaskScope` reaches GA (currently expected ~Java 25), at which point the cancellation ergonomics justify a rewrite.

Related: [[001-stable-stack-baseline]], [[003-workflow-vs-agent-for-payment-decisioning]].
