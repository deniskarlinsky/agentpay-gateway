# ADR-003: Multi-Agent Fan-Out for Payment Decisioning, Despite the Token Cost

**Status:** Accepted
**Date:** 2026-05-19

## Context
`FR-DP-001` and `FR-DP-002` require fanning the `PaymentContext` out to three specialists — risk, compliance, routing — and aggregating their verdicts into one `Decision`. The structural question is: do we run three independent `ChatClient` calls (one per specialist), or one larger agent equipped with three tools? Anthropic's own published guidance puts multi-agent setups at roughly fifteen times the token cost of an equivalent single-agent run. In a real payment system the economics tilt hard toward a deterministic workflow that invokes an LLM only at narrow decision points (e.g., a free-text justification, not the score). This project is not a real payment system. The architecture is the artifact.

## Decision
We implement the Orchestrator-Workers pattern by hand. `Supervisor.java` fans the context out to `RiskAgent`, `ComplianceAgent`, and `RoutingAgent` in parallel on virtual threads via `CompletableFuture.supplyAsync(..., wrappedExecutor)`, applies a 10-second per-call `orTimeout`, and aggregates per the FR-DP-002 rule. Each agent owns its own `ChatClient`, its own prompt, and its own structured-output record. The Supervisor itself makes no external calls (`FR-DP-005`) — its only outputs are the aggregated `Decision` and the parent observation span.

## Consequences
- The token bill is the cost of the demonstration. Per-case spend is bounded by the `agentpay.budget.per_case_usd` circuit breaker, which cancels siblings once the running total trips. See [[007-model-routing]] for the per-agent model split that keeps the baseline manageable.
- Three independent agents are three independent failure domains. Each `exceptionally` branch returns a REVIEW-band sentinel; aggregation stays simple.
- The Langfuse trace shows one root case span with three parallel agent children — the exact shape a reviewer expects from this pattern.
- For a hypothetical production fork, this is the wrong shape. That trade-off is named here, in writing, so the fork can find it.
- We avoid the Spring AI 2.0 `OrchestratorWorkersWorkflow` abstraction. The hand-written supervisor is ~60 lines and one class; the framework version would buy nothing on the pinned stack.

## Alternatives considered
- **One agent with three tools.** The reviewer-default option, and the cheaper one. Rejected: it collapses exactly the multi-agent pattern this repo exists to demonstrate. A single tool-using agent is a different ADR for a different project.
- **`OrchestratorWorkersWorkflow` from Spring AI 2.0.** The "right" framework path. Rejected: Spring AI 2.0 violates [[001-stable-stack-baseline]].
- **Deterministic rules engine with one LLM call for justification text.** The production-correct shape. Rejected for this project: it would not exercise the multi-agent / virtual-thread fan-out / per-agent observability surface that is the point of the build.

## When to revisit
Never, for this project. Immediately, for any production fork — the token math inverts the decision the moment cost-per-case becomes a real constraint.

Related: [[001-stable-stack-baseline]], [[006-virtual-threads-no-preview]], [[007-model-routing]].
