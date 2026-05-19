# AgentPay Gateway — Requirements Specification

| | |
| --- | --- |
| **Version** | 1.1 (stable-stack revision) |
| **Status** | Frozen for MVP implementation |
| **Audience** | Implementing agent (Claude Code) and human reviewers |
| **Target stack** | Java 21 LTS, Spring Boot 3.5.x, Spring AI 1.1.5, Postgres 16 + pgvector, Kafka 3.7, Langfuse |
| **MVP scope** | One end-to-end payment scenario + one compensation scenario, runnable via `make up && make demo` |
| **Stack policy** | No preview Java features. No GA-within-30-days frameworks. No experimental APIs. |

---

## 0. How to use this document

This document is the single source of truth for the AgentPay Gateway MVP. Read it in full before writing code.

### Conventions

The keywords **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

Each requirement has a unique identifier of the form `FR-<area>-<NNN>` for functional requirements or `NFR-<area>-<NNN>` for non-functional. Cross-references use these IDs.

### Implementation guidance for the implementing agent

1. **Implement in priority order.** Within a section, implement MUST requirements before SHOULD before MAY.
2. **Do not add functionality outside this document.** If a feature seems useful but is not specified, leave it out and note it in `docs/roadmap.md`.
3. **Prefer the simpler interpretation.** When a requirement is ambiguous, choose the implementation that adds the least code and the fewest dependencies.
4. **Match the architecture in the README.** This document specifies the contract; the README provides the rationale.
5. **Treat the acceptance criteria as the test specification.** Each requirement has an acceptance criterion that should map to at least one automated test where reasonable.
6. **Use the file paths in the deliverables checklist.** Do not invent alternative module layouts.
7. **Use only stable, GA-released APIs.** No `--enable-preview`, no `-M`/`-RC`/`-SNAPSHOT` dependencies, no `@Experimental`-annotated APIs.

---

## 1. Overview

AgentPay Gateway is an agent-native payment gateway pet project. It demonstrates how a payment platform can authenticate AI agents as clients, authorize transactions initiated on behalf of humans or businesses, and run risk decisions through a multi-agent decision plane while preserving compensability and observability.

The system handles one synthetic end-to-end scenario (a buyer agent paying a merchant within a scoped intent token) and one compensation scenario (a compliance failure that compensates the held funds). All external dependencies (PSP, sanctions feed, merchant data) are mocked.

---

## 2. Glossary

| Term | Definition |
| --- | --- |
| **A2A discovery** | The `AgentCard` JSON document at `/.well-known/agent.json` describing the gateway's agent-facing capabilities. Full A2A protocol (JSON-RPC `sendMessage`) is out of scope; see §4.2. |
| **Agent** | An LLM-driven service that performs one specialized task within the decision plane. |
| **Buyer agent** | An AI agent acting on behalf of a human or business buyer. |
| **Case** | The full lifecycle of one payment request from intent-token issuance to terminal Saga state. |
| **Compensation** | Reversal of side effects of a Saga step when a later step fails or declines. |
| **Decision plane** | The collection of supervisor + specialist agents that produces a `Decision`. |
| **Intent token** | Short-lived signed JWT granting an agent scope-limited authorization to initiate a payment. |
| **MCP** | Model Context Protocol. Open standard for exposing tools and data to AI agents. |
| **Orchestrator** | The Spring Boot service running the Saga and invoking the decision plane. |
| **Saga** | Long-running transaction broken into compensable steps. |
| **Specialist agent** | One of `RiskAgent`, `ComplianceAgent`, or `RoutingAgent`. |
| **Supervisor** | The orchestrating component inside the decision plane that delegates to specialists and aggregates verdicts. |

---

## 3. Personas and actors

| Actor | Description | Interacts via |
| --- | --- | --- |
| Buyer AI agent | External non-human client initiating a payment | REST API on the gateway (with discovery via AgentCard) |
| Merchant agent | External non-human client representing a merchant | REST API on the gateway |
| On-call ops | Human operator approving REVIEW-status decisions | Kafka event consumer (or CLI in MVP) |
| Implementing developer | Human running the project locally | `make` targets and Langfuse / Grafana UIs |

---

## 4. Scope

### 4.1 In scope

- One Spring Boot 3.5.x multi-module Gradle project.
- Five services: gateway, orchestrator, sanctions-mcp, mock-psp, buyer-client.
- One end-to-end happy-path scenario.
- One compensation scenario triggered by compliance failure.
- One human-review escalation scenario producing a Kafka event.
- 10 golden eval cases with deterministic and LLM-as-judge verdicts.
- Observability via Micrometer + OpenTelemetry → Langfuse + Prometheus.
- Local execution via `docker-compose`.
- A2A `AgentCard` discovery endpoint at `/.well-known/agent.json`.

### 4.2 Out of scope

The implementing agent MUST NOT implement:

- Full A2A protocol (JSON-RPC `sendMessage`, capability negotiation, multi-hop, federation). The Spring AI A2A starter currently requires Spring Boot 4.0 + Spring AI 2.0, neither of which meets the stack policy in §0. **Discovery endpoint only.**
- Real PSP integration.
- Real sanctions data feeds.
- Cloud-agnostic Terraform / Helm / Kubernetes.
- Authentication of human users.
- Frontend / web UI.
- More than one MCP server.
- More than three specialist agents.
- Spring State Machine.
- Real chargeback / dispute handling.
- Real merchant onboarding workflow.
- Encryption at rest beyond Postgres defaults.
- Production-grade rate limiting.
- Java preview features (`StructuredTaskScope`, primitive patterns, etc.).
- Use of Claude Opus models.

---

## 5. Functional requirements

### 5.1 Gateway service (`FR-G-*`)

The Gateway is the only externally reachable service.

**FR-G-001 (MUST)** The Gateway exposes `POST /intent-tokens` accepting an `IntentTokenRequest` (see §7.1.1) and returning a signed JWT.

- **Acceptance:** A valid request returns HTTP 200 with a JWT in the response body. The JWT signature verifies against the gateway's published JWKS at `GET /.well-known/jwks.json`.

**FR-G-002 (MUST)** The intent token JWT MUST contain claims: `iss`, `sub` (agent ID), `aud` (merchant ID), `amount_cap`, `currency` (ISO 4217), `exp` (≤ 5 minutes from `iat`), `iat`, `jti` (UUID v4), `scope`, `agent_pubkey_jkt` (JWK thumbprint of the buyer agent's signing key).

- **Acceptance:** A decoded token contains all listed claims. Tokens with `exp - iat > 300` seconds are rejected at issuance.

**FR-G-003 (MUST)** The Gateway exposes `POST /payments` accepting an intent token and a `PaymentRequest` body (see §7.1.2).

- **Acceptance:** Requests with a valid, non-expired, unused token whose claims match the payment request return HTTP 202 with a `case_id`. Requests with `amount_cap < payment.amount`, mismatched `aud`, expired `exp`, or replayed `jti` return HTTP 403 with a structured error.

**FR-G-004 (MUST)** The Gateway MUST reject any payment request whose body conflicts with the intent token's claims.

**FR-G-005 (MUST)** The Gateway exposes `GET /.well-known/agent.json` returning a static `AgentCard` JSON document advertising the gateway's skills (`request_intent_token`, `submit_payment`).

- **Acceptance:** Endpoint returns JSON conforming to the A2A `AgentCard` schema (see §7.1.4). Implementation: one Spring MVC controller returning a constant object. No SDK dependency. Full A2A request handling is out of scope.

**FR-G-006 (MUST)** The Gateway MUST perform PII redaction on any request body before forwarding to the orchestrator. Redaction MUST cover at least: `pan` (16-digit card numbers), `iban`, and any field annotated `@PII` in the OpenAPI schema.

**FR-G-007 (MUST)** The Gateway MUST enforce a per-agent rate limit of 60 requests per minute, sliding window. Implementation: Bucket4j (stable, well-known library) or a simple Redis-backed sliding window.

**FR-G-008 (SHOULD)** The Gateway SHOULD expose `/actuator/prometheus` via Spring Boot Actuator.

### 5.2 Payment Orchestrator (`FR-O-*`)

**FR-O-001 (MUST)** The Orchestrator MUST implement a Saga with states: `INITIATED`, `HELD`, `REVIEWING`, `APPROVED`, `ROUTED`, `COMMITTED`, `DECLINED`, `COMPENSATED`, `SUSPENDED_FOR_REVIEW`.

**FR-O-002 (MUST)** The Orchestrator MUST persist Saga state to Postgres on every transition. State persistence MUST be transactional with the action that triggered the transition.

**FR-O-003 (MUST)** When the Saga enters `HELD`, the Orchestrator MUST invoke the decision plane supervisor and await a `Decision`.

**FR-O-004 (MUST)** If `Decision.outcome == DECLINED`, the Orchestrator MUST execute compensation: release the hold and publish a `payment.declined` event to Kafka.

**FR-O-005 (MUST)** If `Decision.outcome == REVIEW`, the Orchestrator MUST publish a `human.approval.requested` event and transition to `SUSPENDED_FOR_REVIEW`. It MUST NOT proceed without a subsequent `human.approval.granted` or `human.approval.denied` event.

**FR-O-006 (MUST)** If `Decision.outcome == APPROVED`, the Orchestrator MUST call the mock PSP via the routing recommendation and transition to `COMMITTED` on PSP success or `COMPENSATED` on PSP failure.

**FR-O-007 (MUST)** The Orchestrator MUST be idempotent on `case_id`.

**FR-O-008 (MUST)** The Orchestrator MUST execute Saga compensations in reverse order of their forward steps.

**FR-O-009 (MUST)** The Orchestrator MUST publish exactly one terminal Kafka event per Saga: one of `payment.completed`, `payment.declined`, `payment.compensated`.

**FR-O-010 (SHOULD)** The Orchestrator SHOULD emit OpenTelemetry spans for every Saga step, with span attributes `case_id`, `saga_state_from`, `saga_state_to`, `agent_decisions`.

### 5.3 Decision Plane — Supervisor (`FR-DP-*`)

The Supervisor is a plain `@Service` that orchestrates fan-out to specialists. **No external orchestration framework is used.** The orchestration is implemented in ~50 lines of Java using `Executors.newVirtualThreadPerTaskExecutor()` and `CompletableFuture`. Both APIs are stable in Java 21 with no preview flags.

**FR-DP-001 (MUST)** The Supervisor MUST fan out a `PaymentContext` to all three specialist agents in parallel using virtual threads via `Executors.newVirtualThreadPerTaskExecutor()` and `CompletableFuture.allOf(...)`. The executor MUST be properly shut down on application shutdown via `@PreDestroy`.

- **Acceptance:** Trace inspection in Langfuse shows three child spans, started within 50ms of each other.

**FR-DP-002 (MUST)** The Supervisor MUST aggregate the three verdicts into a single `Decision` per the following rule:
- If `ComplianceVerdict.outcome == FAIL` → `Decision.outcome = DECLINED`.
- Else if `RiskAssessment.score >= 80` → `Decision.outcome = DECLINED`.
- Else if `RiskAssessment.score >= 50` or `ComplianceVerdict.outcome == REVIEW` → `Decision.outcome = REVIEW`.
- Else → `Decision.outcome = APPROVED`, with `Decision.route = RoutingRecommendation`.

**FR-DP-003 (MUST)** The Supervisor MUST timeout each specialist invocation at 10 seconds using `CompletableFuture.orTimeout(10, SECONDS)`. A specialist timeout MUST be treated as `REVIEW`.

**FR-DP-004 (MUST)** Spring AI 1.1's Micrometer observability emits chat-model timer, token counters, and provider attributes natively. The orchestrator MUST configure `micrometer-tracing-bridge-otel` to export these via OTLP.

**FR-DP-005 (MUST NOT)** The Supervisor MUST NOT directly call external systems. Its only outputs are the aggregated `Decision` and observability spans.

### 5.4 Specialist Agents

All specialist agents share these requirements:

**FR-A-COMMON-001 (MUST)** Each specialist MUST declare its negative space in the first paragraph of its system prompt.

**FR-A-COMMON-002 (MUST)** Each specialist MUST return a Java `record` matching its verdict type via Spring AI's `.entity(Class)` structured output binding.

**FR-A-COMMON-003 (MUST)** Each specialist MUST be configurable to use a specific model via `application.yml`.

**FR-A-COMMON-004 (MUST)** Each specialist's system prompt MUST be a separate markdown file under `services/orchestrator/src/main/resources/prompts/<agent-name>.md`. Prompts MUST be loaded at startup via `@Value("classpath:prompts/<name>.md")`.

**FR-A-COMMON-005 (SHOULD)** Each specialist's prompt SHOULD include 1-2 worked few-shot examples.

#### 5.4.1 RiskAgent — `claude-sonnet-4-6`

**FR-A-R-001 (MUST)** Returns `RiskAssessment(int score, List<String> signals, String rationale)`.

**FR-A-R-002 (MUST)** Has access to two read-only tools: `velocity_check(agent_id)` and `fraud_rules_lookup(transaction_pattern)`.

**FR-A-R-003 (MUST)** Has no access to state-mutating tools.

**FR-A-R-004 (MUST)** Negative space MUST include: "NEVER modifies state", "NEVER calls PSPs", "NEVER decides approval — only scores risk".

#### 5.4.2 ComplianceAgent — `claude-sonnet-4-6`

**FR-A-C-001 (MUST)** Returns `ComplianceVerdict(Outcome outcome, List<String> citations, String rationale)`.

**FR-A-C-002 (MUST)** Consumes the Sanctions MCP server via Spring AI MCP client. Calls `lookup_sanctions` for the buyer's identity and the merchant's identity on every case.

**FR-A-C-003 (MUST)** If either lookup returns a positive match, the verdict MUST be `FAIL` with the matching citation.

**FR-A-C-004 (MUST)** Negative space MUST include: "NEVER overrides risk decisions", "NEVER touches PII outside the scope of the lookup", "NEVER acts on the result — only reports".

#### 5.4.3 RoutingAgent — `claude-haiku-4-5`

**FR-A-RT-001 (MUST)** Returns `RouteRecommendation(String pspId, String routeId, float expectedSuccessRate, int expectedCostBps, String rationale)`.

**FR-A-RT-002 (MUST)** Consumes routing-metrics RAG from Postgres pgvector; embeddings of recent route performance are pre-seeded by a fixture.

**FR-A-RT-003 (MUST)** Selects a route from a fixed set of three mock PSPs defined in `services/mock-psp`.

### 5.5 Sanctions MCP Server (`FR-M-*`)

**FR-M-001 (MUST)** Implemented using `spring-ai-starter-mcp-server-webmvc` (servlet-based, synchronous, the simplest stable variant). Exposes one `@McpTool`-annotated method: `lookup_sanctions(name, country) → SanctionsResult`.

**FR-M-002 (MUST)** `SanctionsResult(boolean isMatch, Float matchStrength, String listName, String citationId)`. Last three fields are null when `isMatch == false`.

**FR-M-003 (MUST)** Sanctions data MUST come from a static JSON fixture at `services/sanctions-mcp/src/main/resources/fixtures/sanctions.json` containing at least 20 obviously-synthetic entries.

**FR-M-004 (MUST)** Matching MUST be case-insensitive and tolerate one Levenshtein-distance typo for entries longer than 5 characters.

**FR-M-005 (MUST)** Reachable from the orchestrator container; service name `sanctions-mcp`, port 8090.

**FR-M-006 (SHOULD)** Logs every tool invocation with `case_id` propagated through W3C trace context.

### 5.6 Mock PSP (`FR-P-*`)

**FR-P-001 (MUST)** Spring Boot 3.5 service exposing `POST /charge` accepting a `ChargeRequest` and returning a `ChargeResponse`.

**FR-P-002 (MUST)** Three configurable PSP profiles: `psp-a` (95% success, 30bps), `psp-b` (88% success, 20bps), `psp-c` (98% success, 45bps). Outcomes MUST be deterministic given a stable `case_id`.

**FR-P-003 (MUST)** Emits ISO 20022 reason codes on failure: at least `AC01`, `AM04`, `DT03`.

### 5.7 Buyer Client (`FR-B-*`)

**FR-B-001 (MUST)** CLI accepting flags: `--intent`, `--merchant`, `--amount`, `--scenario <happy|compliance-fail|review>`.

**FR-B-002 (MUST)** Requests an intent token, signs the payment request with its own keypair, submits the payment.

**FR-B-003 (MUST)** Prints the final Saga state and a clickable Langfuse trace URL.

**FR-B-004 (MAY)** Persists keypair between runs at `~/.agentpay/buyer-key.pem`.

### 5.8 Evals (`FR-E-*`)

**FR-E-001 (MUST)** `evals/golden_cases.json` MUST contain at least 10 cases. Each case: `case_id`, `payment_request`, `expected_decision`, `expected_reason_class`, `optional_tool_calls`.

**FR-E-002 (MUST)** Evals run via `./gradlew :evals:test` and are wired into `.github/workflows/ci.yml` as a required job.

**FR-E-003 (MUST)** Two judge types:
1. **Deterministic**: verifies Saga compensation, PII redaction, scope honoring, tool-call assertions.
2. **LLM-as-judge** (Claude Haiku 4.5): grades `rationale` 0-5 with critique.

**FR-E-004 (MUST)** Results written to `evals/results/<timestamp>.json` plus stdout summary.

**FR-E-005 (SHOULD)** Regression threshold: `mean_llm_judge_score >= 4.0` AND `deterministic_pass_rate == 1.0` blocks CI on failure.

---

## 6. Non-functional requirements

### 6.1 Performance (`NFR-P-*`)

**NFR-P-001 (MUST)** End-to-end p95 latency from `POST /payments` to terminal Saga state ≤ 8 seconds on a developer laptop.

**NFR-P-002 (MUST)** Decision plane parallel fan-out wall-clock latency ≤ 1.5× the slowest specialist's latency.

**NFR-P-003 (SHOULD)** Saga state-transition DB operations p95 ≤ 50ms.

### 6.2 Observability (`NFR-O-*`)

**NFR-O-001 (MUST)** All services emit OTLP traces to a local OTel collector, which forwards to Langfuse and to a file exporter at `ops/traces/`.

**NFR-O-002 (MUST)** Spring AI 1.1 emits chat-model observability via Micrometer natively. The orchestrator MUST configure `micrometer-tracing-bridge-otel` to export.

**NFR-O-003 (MUST)** Per-case cost (USD) MUST be calculated and attached as `agentpay.case.cost_usd` on the root case span. Cost = token usage × per-model rates from `application.yml`.

**NFR-O-004 (MUST)** Grafana dashboard at `ops/grafana/dashboards/agentplane.json` showing: cost per case (median, p95, max), decision-rate by outcome, compensation rate, specialist latency p95, eval pass rate.

**NFR-O-005 (MUST)** All services expose `/actuator/health`, `/actuator/info`, `/actuator/prometheus`.

### 6.3 Security (`NFR-S-*`)

**NFR-S-001 (MAY)** Plain HTTP in `compose-local`. HTTPS is out of scope for the MVP.

**NFR-S-002 (MUST)** Intent-token signing key configurable via `GATEWAY_SIGNING_KEY_PEM`. Not committed. Generated at startup to `.local/gateway-key.pem` (gitignored) if absent.

**NFR-S-003 (MUST)** PII redaction (`FR-G-006`) implemented as a Spring AI `Advisor` on the `ChatClient`.

**NFR-S-004 (MUST)** Per-agent tool allowlists enforced. Startup MUST reject any agent registering a tool not on its allowlist.

**NFR-S-005 (MUST)** Buyer signatures verified against `agent_pubkey_jkt` from the intent token.

**NFR-S-006 (MUST)** Intent tokens rejected on replay: `jti` stored in Redis with TTL = token `exp`.

**NFR-S-007 (MUST)** MCP client connections allowlist MCP servers by URL.

**NFR-S-008 (SHOULD)** `docs/threat-model.md` covers: prompt injection via merchant metadata, intent-token replay, MCP lookalike tools, agent-identity impersonation, token-exhaustion cost attack.

### 6.4 Reliability (`NFR-R-*`)

**NFR-R-001 (MUST)** Every Saga compensation step idempotent.

**NFR-R-002 (MUST)** Orchestrator recovers from a process crash mid-Saga: on startup, any Saga in a non-terminal state resumes from its last persisted state.

**NFR-R-003 (MUST)** Kafka publishes transactional with the Saga state transition that triggered them.

**NFR-R-004 (SHOULD)** MCP client supports retry with exponential backoff (3 retries, 100ms initial, ×2).

### 6.5 Quality (`NFR-Q-*`)

**NFR-Q-001 (MUST)** Test coverage ≥ 70% line coverage on gateway and orchestrator.

**NFR-Q-002 (MUST)** Structured-output verdicts validate against their Java record schema. Validation failures retried once; second failure → `REVIEW`.

**NFR-Q-003 (MUST)** CI pipeline runs: compile → unit tests → integration tests (Testcontainers) → evals. Any failure fails the pipeline.

**NFR-Q-004 (SHOULD)** Static analysis includes `spotless` and `errorprone`.

### 6.6 Cost (`NFR-COST-*`)

**NFR-COST-001 (MUST)** Per-case cost budget enforced as a circuit breaker. Default $0.10 USD. Once exceeded, supervisor short-circuits to `REVIEW` and emits `case.budget_exceeded`.

**NFR-COST-002 (MUST)** Model rates loaded from `application.yml`. Defaults:
- `claude-haiku-4-5`: input $1.00 / output $5.00 per MTok.
- `claude-sonnet-4-6`: input $3.00 / output $15.00 per MTok.

**NFR-COST-003 (SHOULD)** Total monthly cost during development < $10 USD when running the demo 50 times and the eval suite 20 times.

### 6.7 Developer experience (`NFR-DX-*`)

**NFR-DX-001 (MUST)** Single `make up` brings up the entire system from a clean checkout, given Docker, Java 21, and `ANTHROPIC_API_KEY`.

**NFR-DX-002 (MUST)** `make demo` runs happy + compensation scenarios back-to-back, prints clickable Langfuse trace URLs.

**NFR-DX-003 (MUST)** No cloud account required.

**NFR-DX-004 (MUST)** README documents the full quick start.

**NFR-DX-005 (SHOULD)** Prompt edits in `services/orchestrator/src/main/resources/prompts/*.md` picked up without rebuild (Spring DevTools).

### 6.8 Privacy (`NFR-PII-*`)

**NFR-PII-001 (MUST)** No real PII in fixtures, eval cases, log files, or trace exports.

**NFR-PII-002 (MUST)** Trace exports scrub LLM request/response payloads of card-number-regex matches before persistence.

---

## 7. API contracts

### 7.1 Gateway REST API

#### 7.1.1 `POST /intent-tokens`

```json
// Request
{
  "agent_id": "agent-buyer-001",
  "agent_pubkey": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----",
  "merchant_id": "merchant-acme",
  "amount_cap": "50.00",
  "currency": "USD",
  "scope": "purchase:sku-42",
  "ttl_seconds": 300
}

// Response (HTTP 200)
{
  "intent_token": "eyJhbGciOiJSUzI1NiI...",
  "expires_at": "2026-05-13T12:05:00Z"
}
```

#### 7.1.2 `POST /payments`

```http
Authorization: Bearer <intent_token>
Content-Type: application/json

{
  "case_id": "case-7f2a91c0",
  "merchant_id": "merchant-acme",
  "amount": "42.50",
  "currency": "USD",
  "description": "SKU-42 widget",
  "buyer_signature": "<base64-signature-over-canonical-form>"
}

// Response (HTTP 202)
{
  "case_id": "case-7f2a91c0",
  "status": "ACCEPTED",
  "trace_url": "http://localhost:3000/traces/<id>"
}
```

#### 7.1.3 `GET /cases/{case_id}`

```json
{
  "case_id": "case-7f2a91c0",
  "state": "COMMITTED",
  "decision": {
    "outcome": "APPROVED",
    "risk_score": 12,
    "compliance": "PASS",
    "route": {"psp_id": "psp-a", "route_id": "route-eu-1"}
  },
  "psp_outcome": {"status": "SUCCESS", "auth_code": "AUTH-93812"},
  "trace_url": "..."
}
```

#### 7.1.4 `GET /.well-known/agent.json`

```json
{
  "name": "AgentPay Gateway",
  "description": "Agent-native payment gateway. Authenticates AI agents and authorizes scoped payments.",
  "url": "http://localhost:8080/",
  "version": "0.1.0",
  "protocolVersion": "0.3.0",
  "capabilities": {"streaming": false},
  "defaultInputModes": ["application/json"],
  "defaultOutputModes": ["application/json"],
  "skills": [
    {"id": "request_intent_token", "name": "Request intent token", "description": "Obtain a scoped, short-lived JWT to authorize one payment.", "tags": ["payments", "authorization"]},
    {"id": "submit_payment", "name": "Submit payment", "description": "Submit a payment authorized by a prior intent token.", "tags": ["payments"]}
  ]
}
```

### 7.2 Decision Plane internal contracts

#### 7.2.1 `Decision`

```java
public record Decision(
    Outcome outcome,                    // APPROVED, DECLINED, REVIEW
    int riskScore,                       // 0-100
    ComplianceVerdict compliance,
    Optional<RouteRecommendation> route, // present iff outcome == APPROVED
    List<String> rationale               // one line per specialist
) {}
```

#### 7.2.2 `PaymentContext`

```java
public record PaymentContext(
    String caseId,
    String agentId,
    String merchantId,
    BigDecimal amount,
    String currency,
    String description,
    Map<String, String> agentMetadata    // never contains PII; gateway-redacted
) {}
```

### 7.3 MCP server tools

```java
@McpTool(description = "Look up a person or entity name against synthetic sanctions/PEP fixture data.")
public SanctionsResult lookup_sanctions(
    @McpToolParam(description = "Full name", required = true) String name,
    @McpToolParam(description = "ISO 3166-1 alpha-2 country code") String country) {
  // ...
}
```

### 7.4 Kafka topics

| Topic | Schema | Producer | Consumer |
| --- | --- | --- | --- |
| `payment.events` | `PaymentEvent` (terminal only) | Orchestrator | eval consumer, on-call CLI |
| `human.approval.requests` | `HumanApprovalRequest` | Orchestrator | On-call CLI |
| `human.approval.responses` | `HumanApprovalResponse` | On-call CLI | Orchestrator |
| `case.budget_exceeded` | `BudgetExceededEvent` | Orchestrator | observability |

All schemas defined as Avro under `shared/api-contracts/avro/`.

---

## 8. Data model

```sql
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

CREATE TABLE saga_transitions (
    id          BIGSERIAL PRIMARY KEY,
    case_id     VARCHAR(64) REFERENCES cases(case_id),
    state_from  VARCHAR(32),
    state_to    VARCHAR(32) NOT NULL,
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

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

CREATE TABLE route_metrics (
    psp_id          VARCHAR(64),
    route_id        VARCHAR(64),
    embedding       vector(1536),
    metrics_json    JSONB NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (psp_id, route_id)
);
```

---

## 9. Configuration

`gradle/libs.versions.toml` — pinned versions:

```toml
[versions]
java = "21"
spring-boot = "3.5.4"
spring-ai = "1.1.5"
postgres-driver = "42.7.4"
pgvector = "0.1.6"
flyway = "10.20.1"
nimbus-jose-jwt = "9.40"
testcontainers = "1.20.4"
bucket4j = "8.10.1"
```

Docker image versions:

```yaml
postgres:        postgres:16-alpine        # with pgvector extension installed
kafka:           apache/kafka:3.7.x        # KRaft mode
redis:           redis:7-alpine
langfuse:        langfuse/langfuse:3       # latest stable
otel-collector:  otel/opentelemetry-collector-contrib:0.110.0
prometheus:      prom/prometheus:v2.55.0
grafana:         grafana/grafana:11.3.0
```

`application.yml`:

```yaml
agentpay:
  models:
    risk: claude-sonnet-4-6
    compliance: claude-sonnet-4-6
    routing: claude-haiku-4-5
    judge: claude-haiku-4-5
  rates:
    claude-haiku-4-5:   { input_per_mtok: 1.00, output_per_mtok: 5.00 }
    claude-sonnet-4-6:  { input_per_mtok: 3.00, output_per_mtok: 15.00 }
  budget:
    per_case_usd: 0.10
  saga:
    specialist_timeout_seconds: 10
  rate_limit:
    requests_per_minute_per_agent: 60

spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
    mcp:
      client:
        streamable-http:
          connections:
            sanctions:
              url: http://sanctions-mcp:8090
```

Env vars:

| Variable | Purpose | Required |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | Anthropic API access | yes |
| `GATEWAY_SIGNING_KEY_PEM` | JWT signing | auto-generated if absent |
| `POSTGRES_PASSWORD` | DB | docker-compose default fine |

---

## 10. Acceptance test scenarios

Each scenario MUST be implemented as an end-to-end integration test under `services/orchestrator/src/test/java/.../e2e/`.

### 10.1 Scenario A — Happy path

**Given** a buyer agent with a valid keypair,
**when** it requests an intent token for $42.50 at `merchant-acme` and submits a `POST /payments`,
**then** the case reaches state `COMMITTED`, the mock PSP records the charge, exactly one `payment.completed` Kafka event is published, and the Langfuse trace contains a root span with three parallel agent child spans.

### 10.2 Scenario B — Compliance failure → declined

**Given** a buyer agent whose name matches a sanctions fixture entry,
**when** it submits a payment,
**then** the case reaches state `DECLINED` (no funds were held, no compensation needed),
exactly one `payment.declined` Kafka event is published with `reason_class = COMPLIANCE_SANCTIONS_MATCH`,
and the ComplianceAgent verdict cites the matching `citation_id`.

### 10.3 Scenario C — Risk review → human approval

**Given** a payment with risk signals that yield `RiskAssessment.score == 65`,
**when** the supervisor aggregates verdicts,
**then** the case enters `SUSPENDED_FOR_REVIEW`, a `human.approval.requested` Kafka event is published, and the Saga does not progress until the on-call CLI publishes `human.approval.granted`.

### 10.4 Scenario D — Intent token scope violation

**Given** an intent token with `amount_cap = 50`,
**when** the buyer submits a payment with `amount = 75`,
**then** the gateway rejects with HTTP 403 and `error_code = SCOPE_AMOUNT_EXCEEDED`. No case is created.

### 10.5 Scenario E — Intent token replay

**Given** an intent token already used once,
**when** the buyer submits a second payment with the same token,
**then** the gateway rejects with HTTP 403 and `error_code = TOKEN_REPLAYED`.

### 10.6 Scenario F — Cost budget exceeded

**Given** an orchestrator configured with `per_case_usd = 0.001`,
**when** any case is processed,
**then** the supervisor short-circuits before the third specialist call, the case enters `SUSPENDED_FOR_REVIEW`, and a `case.budget_exceeded` event is published.

### 10.7 Scenario G — Orchestrator crash recovery

**Given** a Saga in state `HELD`,
**when** the orchestrator process is killed and restarted,
**then** the Saga resumes and reaches a terminal state within 10 seconds.

---

## 11. Deliverables checklist

### 11.1 Code

- [x] `services/gateway/`
- [x] `services/orchestrator/`
- [ ] `services/orchestrator/src/main/resources/prompts/risk.md`
- [ ] `services/orchestrator/src/main/resources/prompts/compliance.md`
- [ ] `services/orchestrator/src/main/resources/prompts/routing.md`
- [ ] `services/orchestrator/src/main/resources/skills/iso20022-reason-codes.md`
- [x] `services/sanctions-mcp/`
- [x] `services/mock-psp/`
- [ ] `services/buyer-client/`
- [x] `shared/api-contracts/`

### 11.2 Tests

- [ ] Unit tests per service (≥ 70% on gateway and orchestrator)
- [ ] Integration tests for all 7 acceptance scenarios
- [ ] `evals/golden_cases.json` with 10 cases
- [ ] `evals/src/test/java/` deterministic and LLM-as-judge runners

### 11.3 Build & ops

- [ ] `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`
- [ ] `docker-compose.yml` (Postgres 16, Bitnami Kafka 3.7, Redis 7, Langfuse 3, OTel collector, all services)
- [ ] `Makefile`: up / down / demo / test / eval / eval-watch / logs / langfuse / grafana / trace
- [x] `.github/workflows/ci.yml`: build / unit-test / integration-test / eval jobs
- [ ] `ops/grafana/dashboards/agentplane.json`
- [ ] `ops/prometheus/prometheus.yml`
- [ ] `ops/otel-collector/otel-collector-config.yml`

### 11.4 Documentation

- [ ] `README.md`
- [ ] `docs/architecture.md`
- [ ] `docs/adr/001-stable-stack-baseline.md`
- [ ] `docs/adr/002-saga-coordinator-vs-state-machine.md`
- [ ] `docs/adr/003-workflow-vs-agent-for-payment-decisioning.md`
- [ ] `docs/adr/004-a2a-discovery-only.md`
- [ ] `docs/adr/005-one-mcp-server.md`
- [ ] `docs/adr/006-virtual-threads-no-preview.md`
- [ ] `docs/adr/007-model-routing.md`
- [ ] `docs/adr/008-evals-as-ci-gate.md`
- [ ] `docs/threat-model.md`
- [ ] `docs/saga-states.md`

---

## 12. Definition of done

The MVP is **done** when all are true:

1. From a clean checkout with Docker, Java 21, `ANTHROPIC_API_KEY`:
   - `make up` ≤ 5 minutes.
   - `make demo` runs Scenarios A and B, prints two Langfuse URLs.
   - `make test` green.
2. `.github/workflows/ci.yml` green on main.
3. All items in §11 checked.
4. Grafana dashboard renders all five panels with data after demo.
5. Langfuse UI shows parallel specialist fan-out.
6. No `--enable-preview`, no `-M`/`-RC`/`-SNAPSHOT` deps anywhere in the build.

Tag `v0.1-mvp` when all six are true. Stop.
