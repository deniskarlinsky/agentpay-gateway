# ISO 20022 Reason Codes — AgentPay reference

This reference exists because failure reasons in AgentPay arrive from two sources — the mock PSP (per FR-P-003) and the decision-plane agents — and downstream consumers (Kafka `payment.declined` events, eval golden-case assertions, the on-call CLI) need a single mapping from external codes to internal `reasonClass` labels. The mock PSP emits a fixed subset of ISO 20022 `ExternalReturnReason1Code` values (AC01, AM04, DT03 at minimum); the eval suite asserts that the emitted `reasonClass` matches the golden case's `expectedReasonClass`; compliance and risk verdict failures map to their own internal `reasonClass` values for the same downstream consumers. Sources of truth: the public ISO 20022 `ExternalReturnReason1Code` list (ISO20022.org) and the mock PSP implementation under `services/mock-psp`.

## Codes the mock PSP emits

| Code | ISO meaning (short) | Trigger in mock-psp profile | Maps to internal reasonClass | Notes |
| --- | --- | --- | --- | --- |
| AC01 | IncorrectAccountNumber | Synthetic: case_id hash lands in a per-profile "bad-account" slice | PSP_INCORRECT_ACCOUNT | All three profiles. Permanent failure — orchestrator does not retry. |
| AC04 | ClosedAccountNumber | Synthetic: case_id hash lands in a 0.5% "closed-account" slice | PSP_CLOSED_ACCOUNT | psp-b and psp-c. Permanent. |
| AC06 | BlockedAccount | Synthetic: `agent_id` appears in a small per-profile blocklist | PSP_BLOCKED_ACCOUNT | All three. Permanent; orchestrator emits `payment.declined` without retry. |
| AG01 | TransactionForbidden | Synthetic: (amount, currency) flagged out-of-policy for psp-a | PSP_TRANSACTION_FORBIDDEN | psp-a only. One of the failure paths inside the 5% miss-rate budget per FR-P-002. |
| AM04 | InsufficientFunds | Synthetic: amount > deterministic "balance" derived from case_id | PSP_INSUFFICIENT_FUNDS | All three. The most common failure path. |
| AM05 | Duplication | Synthetic: case_id seen within the mock-psp dedupe window | PSP_DUPLICATE | All three. Defensive only — orchestrator idempotency (FR-O-007) normally prevents this. |
| DT03 | InvalidNonProcessingDate | Synthetic: submission timestamp lands on a fixture-flagged non-processing date | PSP_INVALID_DATE | psp-c only. Rare; exercises the date-validation path. |

## Mapping precedence

When more than one reasonClass could apply to a case, the orchestrator emits the most upstream one:

1. **Gateway-level rejection** (e.g. `SCOPE_AMOUNT_EXCEEDED`, `TOKEN_REPLAYED`) — pre-case; HTTP 403; no `payment.declined` event.
2. **Decision-plane DECLINED** (e.g. `COMPLIANCE_SANCTIONS_MATCH`) — PSP is never called; the decision reasonClass is emitted on `payment.declined`.
3. **Decision-plane REVIEW → eventual denial** — `HUMAN_REVIEW_DENIED` is emitted on `payment.declined` once the on-call CLI publishes `human.approval.denied`.
4. **PSP failure** (e.g. `AM04` → `PSP_INSUFFICIENT_FUNDS`) — emitted on `payment.declined`. Implies the case reached `APPROVED` before charging.

## Codes the decision plane emits (non-ISO)

| Internal reasonClass | Emitting agent | Trigger |
| --- | --- | --- |
| COMPLIANCE_SANCTIONS_MATCH | ComplianceAgent | `lookup_sanctions` returns `isMatch=true` for buyer or merchant (FR-A-C-003, Scenario B). |
| RISK_VELOCITY_SPIKE | RiskAgent | `velocity_check` counters drive `RiskAssessment.score` into the DECLINE band (≥80). |
| RISK_FRAUD_RULE_MATCH | RiskAgent | `fraud_rules_lookup` returns a HIGH-severity rule that drives `score` into the DECLINE band. |
| SPECIALIST_TIMEOUT | Supervisor | A specialist exceeds the 10s `CompletableFuture.orTimeout` window (FR-DP-003); aggregated outcome is REVIEW. |
| BUDGET_EXCEEDED | Supervisor | Cumulative case cost crosses `agentpay.budget.per_case_usd` mid-fan-out (NFR-COST-001, Scenario F); aggregated outcome is REVIEW. |
| HUMAN_REVIEW_DENIED | On-call CLI / Orchestrator | A `SUSPENDED_FOR_REVIEW` Saga receives `human.approval.denied` (Scenario C inverse). |

## Where these codes flow

- PSP codes appear in `mock-psp` `ChargeResponse.reasonCode` → the orchestrator persists them on the case's compensation record → emitted on the `payment.declined` Kafka event as `psp_reason_code`, with the internal `reasonClass` from the third column above as `reasonClass`.
- Internal reason classes are derived from agent verdicts (compliance citations, risk signals) or Supervisor decisions (timeouts, budget) → aggregated into the case's compensation record → emitted on the `payment.declined` Kafka event as `reasonClass`.
- The eval suite asserts on `reasonClass` per the golden case's `expectedReasonClass` field (FR-E-001); failures here block CI per FR-E-005.

## Out of scope

The full `ExternalReturnReason1Code` list (~80 entries) is not exhaustively mapped here. The mock PSP emits the subset above; a production extension is captured in `docs/roadmap.md`.
