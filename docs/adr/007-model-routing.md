# ADR-007: Route Specialist Agents to Haiku 4.5 vs Sonnet 4.6 by Workload

**Status:** Accepted
**Date:** 2026-05-19

## Context
`REQUIREMENTS.md` §5.4 assigns each specialist a model, and `application.yml`'s `agentpay.models` block makes the mapping configurable without recompiling. The pet/portfolio scope (`CLAUDE.md` §1) says "reasoning into code, not into prompts" — which model handles which agent is a code-level architectural decision, not a runtime prompt knob. The two workloads differ sharply: `RoutingAgent` is a three-way classification over candidates returned by pgvector; `RiskAgent` and `ComplianceAgent` produce structured rationales that must survive `.entity()` parsing on the first try. Cost is bounded by `NFR-COST-001` (per-case target $0.10) and `NFR-COST-003` (dev budget $10/month).

## Decision
We use Haiku 4.5 (`claude-haiku-4-5-20251001`) for `RoutingAgent` and Sonnet 4.6 (`claude-sonnet-4-6`) for `RiskAgent` and `ComplianceAgent`. No agent uses Opus — banned by `CLAUDE.md` §4. The mapping lives in `application.yml` under `agentpay.models.{routing,risk,compliance}` and is injected into each agent's `ChatClient` builder.

## Consequences
- Routing is the cheapest specialist call ($1 in / $5 out per MTok); risk + compliance pay $3 / $15 per MTok for reasoning fidelity.
- The per-case cost target ($0.10, `NFR-COST-001`) holds with this split — verified by the cost-budget circuit breaker added in the observability iteration.
- Model choice is configurable but not advertised as a prompt-tuning surface. Changes go through a commit + ADR amendment, not a config push.
- Eval gating (see [ADR-008](008-evals-as-ci-gate.md)) catches per-agent quality regressions if either model drifts.
- Three model identifiers must stay pinned across `application.yml`, eval fixtures, and Testcontainers profiles. Drift is a real risk.

## Alternatives considered
- **Opus everywhere** — rejected: adds a third moving part, `NFR-COST-003` blows out at the dev budget, and `CLAUDE.md` §4 forbids it.
- **Haiku everywhere** — the cheap-and-uniform default. Rejected: Iter 4b.1 prompt-rendering tests showed Haiku 4.5 misses structured-output retries on complex risk rationale more often than Sonnet, breaking `.entity()` mapping.
- **Sonnet for routing too** — rejected: overkill for a three-candidate classification fed by pgvector; doubles the cheapest-call cost for no measurable accuracy gain on the eval set.

## When to revisit
When eval scores for `RiskAgent` or `ComplianceAgent` fall below the `NFR-Q` threshold despite prompt iteration — at that point, promote risk-only to a stronger model inside a roadmap iteration rather than blanket-upgrading.

Related: [[003-workflow-vs-agent-for-payment-decisioning]], [[008-evals-as-ci-gate]].
