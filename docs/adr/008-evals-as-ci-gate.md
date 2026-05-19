# ADR-008: Treat Eval Regression as a CI Failure

**Status:** Accepted
**Date:** 2026-05-19

## Context
The multi-agent decision plane is intrinsically non-deterministic — same input, different rationales — so unit tests can't pin its quality. `FR-E-003` requires both deterministic and LLM-as-judge evaluation; `NFR-Q-003` and `FR-E-005` require those signals to gate merges. Without an eval gate in CI, prompt drift, model snapshot drift, or an unrelated refactor that perturbs context assembly ships silently and is discovered only when a case behaves badly in the demo. The evals already exist as `evals/golden_cases.json` and run via a JUnit test target.

## Decision
We run `./gradlew :evals:test` inside `.github/workflows/ci.yml` as a required check on every PR. Two judges execute against the golden cases: deterministic assertions (`FR-E-003.1`) and an LLM-as-judge prompt scored 1–5 by Haiku 4.5 (`FR-E-003.2`). The gate fails the pipeline when `deterministic_pass_rate < 1.0` OR `mean_llm_judge_score < 4.0`.

## Consequences
- Every PR pays the eval API cost (~$0.05–0.10 per run on the 10 golden cases). Budgeted against `NFR-COST-003`.
- Evals are version-controlled fixtures; a failing eval is fixed by either improving the prompt/code or, when the behaviour change is intentional, updating the expected outcome in the same commit with a justifying message.
- Prompt drift, context-assembly bugs, and model-snapshot regressions surface at PR time, not at demo time.
- The LLM judge uses Haiku 4.5 (see [ADR-007](007-model-routing.md)) — judge drift is a real failure mode, mitigated by pinning the dated snapshot.
- CI now has a non-zero false-failure rate driven by model nondeterminism near the 4.0 threshold. Re-running a flaky eval is an explicit, logged action.

## Alternatives considered
- **Evals as a nightly cron** — the standard ML-Ops default. Rejected: drift survives one merge cycle and lands in `main` before anyone sees it.
- **Human review only** — rejected: non-scaling, unreliable across reviewers, and the project has one maintainer.
- **Deterministic judge only, no LLM judge** — rejected: defeats the point of evals for a reasoning system — most regressions are in rationale quality, not in the structured outcome field.

## When to revisit
When the LLM call cost on the eval suite eclipses a meaningful per-PR overhead — split to a representative subset on PR + full nightly run.

Related: [[007-model-routing]], [[003-workflow-vs-agent-for-payment-decisioning]].
