# Architecture — AgentPay Gateway

> Companion to the top-level `README.md`. Cross-references the ADRs under `docs/adr/`. Source of
> truth for code-level contracts is `REQUIREMENTS.md`.

## Mission, in one paragraph

AgentPay Gateway is an agent-native payment gateway pet/portfolio project. It demonstrates how a
payment platform can authenticate AI agents as clients, authorize transactions initiated on behalf
of humans or businesses, and run risk decisions through a multi-agent decision plane — while
preserving compensability (a Saga), observability (Langfuse + OpenTelemetry + Prometheus +
Grafana), and a hard cost ceiling. **The architecture is the artifact, not a production system.**

## Conceptual view

```mermaid
graph LR
    Buyer[Buyer agent CLI<br/>services/buyer-client]
    Buyer -- POST /intent-tokens<br/>POST /payments --> Gateway

    subgraph Edge
        Gateway[Gateway<br/>services/gateway<br/>JWT • PII redact • rate limit]
    end

    Gateway -- POST /internal/payments --> Orchestrator

    subgraph DecisionPlane[Decision Plane &mdash; virtual threads]
        Supervisor[Supervisor<br/>aggregates verdicts]
        Risk[RiskAgent<br/>Sonnet 4.6]
        Compliance[ComplianceAgent<br/>Sonnet 4.6]
        Routing[RoutingAgent<br/>Haiku 4.5]
        Supervisor --> Risk
        Supervisor --> Compliance
        Supervisor --> Routing
    end

    Orchestrator[Orchestrator<br/>services/orchestrator<br/>Saga + outbox]
    Orchestrator --> Supervisor
    Compliance -- MCP<br/>lookup_sanctions --> Sanctions[Sanctions MCP<br/>services/sanctions-mcp]
    Routing -- pgvector<br/>similaritySearch --> PG[(Postgres 16<br/>+ pgvector)]
    Orchestrator --> PG
    Orchestrator -- POST /charge --> PSP[Mock PSP<br/>services/mock-psp]
    Orchestrator -- transactional outbox --> Kafka[(Kafka 3.7<br/>KRaft)]

    Anthropic[(Anthropic API<br/>claude-sonnet-4-6<br/>claude-haiku-4-5)]
    Risk -.-> Anthropic
    Compliance -.-> Anthropic
    Routing -.-> Anthropic
```

The Supervisor fan-out is the architectural focal point: three ChatClients, three records, one
deterministic aggregator. See [ADR-003](adr/003-workflow-vs-agent-for-payment-decisioning.md) for
why this shape was chosen despite costing ~15× the tokens of a single-agent design, and
[ADR-006](adr/006-virtual-threads-no-preview.md) for why virtual threads + `CompletableFuture`
replace `StructuredTaskScope`.

## C4 Container view

```mermaid
C4Context
    title AgentPay Gateway — Container View

    Person(buyer, "Buyer agent", "AI agent acting for a human/business buyer")
    Person(oncall, "On-call ops", "Human approver for REVIEW outcomes")

    System_Boundary(c1, "AgentPay Gateway") {
        Container(gateway, "Gateway", "Spring Boot 3.5 / Java 21", "JWT intent tokens, A2A discovery, PII redaction, rate limit")
        Container(orchestrator, "Orchestrator", "Spring Boot 3.5 / Spring AI 1.1.5", "Saga coordinator, decision plane, outbox publisher")
        Container(sanctions, "Sanctions MCP", "Spring AI MCP server", "lookup_sanctions tool over a static fixture")
        Container(mockpsp, "Mock PSP", "Spring Boot 3.5", "Deterministic POST /charge with three profiles")
        Container(buyerclient, "Buyer Client", "Spring Boot CLI", "Drives end-to-end demo scenarios")

        ContainerDb(pg, "Postgres 16", "with pgvector 0.7.x", "cases, saga_transitions, event_outbox, agent_verdicts, route_metrics")
        ContainerDb(redis, "Redis 7", "Bucket4j + jti store", "Per-agent rate limit, intent-token replay")
        ContainerDb(kafka, "Kafka 3.7", "KRaft mode", "payment.events, human.approval.*, case.budget_exceeded")

        Container(otel, "OTel Collector", "otel-collector-contrib", "OTLP receiver → Langfuse + file + Prometheus")
        Container(langfuse, "Langfuse 3", "Self-hosted", "LLM trace UI")
        Container(prom, "Prometheus + Grafana", "v2.55 / 11.3", "Metrics + agentplane.json dashboard")
    }

    System_Ext(anthropic, "Anthropic API", "claude-sonnet-4-6, claude-haiku-4-5")

    Rel(buyer, gateway, "REST", "HTTPS in prod, HTTP local")
    Rel(buyerclient, gateway, "REST", "Demo driver")
    Rel(gateway, orchestrator, "REST", "POST /internal/payments")
    Rel(gateway, redis, "TCP")
    Rel(orchestrator, pg, "JDBC")
    Rel(orchestrator, kafka, "Kafka client (transactional)")
    Rel(orchestrator, sanctions, "MCP streamable-http")
    Rel(orchestrator, mockpsp, "REST", "POST /charge")
    Rel(orchestrator, anthropic, "REST", "via Spring AI ChatClient")
    Rel(oncall, kafka, "Consume + produce", "human.approval.responses")

    Rel(gateway, otel, "OTLP")
    Rel(orchestrator, otel, "OTLP")
    Rel(otel, langfuse, "OTLP HTTP")
    Rel(otel, prom, "Prometheus metrics scrape")
```

## Reading guide — what to look at, in order

1. **`REQUIREMENTS.md`** — the contract. Every `FR-*`/`NFR-*` ID below resolves there.
2. **`docs/saga-states.md`** — the 9-state Saga diagram and the compensation rule. Pair with
   [ADR-002](adr/002-saga-coordinator-vs-state-machine.md) and
   [ADR-010](adr/010-transactional-outbox-vs-chained-kafka-tx-manager.md).
3. **`services/orchestrator/src/main/java/com/agentpay/orchestrator/decision/Supervisor.java`** —
   the multi-agent fan-out core. ~50 lines of business logic. Pair with
   [ADR-003](adr/003-workflow-vs-agent-for-payment-decisioning.md) and
   [ADR-006](adr/006-virtual-threads-no-preview.md).
4. **`services/orchestrator/src/main/resources/prompts/{risk,compliance,routing}.md`** — the
   specialist system prompts. Note the **negative space** declaration at the top of each.
5. **`services/gateway/src/main/java/com/agentpay/gateway/web/IntentTokenController.java`** — JWT
   issuance and scope binding. Pair with `docs/threat-model.md`.
6. **`docs/adr/`** — ten ADRs documenting every load-bearing design decision.

## Cross-link: ADR index

| ADR | Subject |
|---|---|
| [001](adr/001-stable-stack-baseline.md) | Pin to Java 21 + Spring Boot 3.5 + Spring AI 1.1.5 |
| [002](adr/002-saga-coordinator-vs-state-machine.md) | Explicit Saga coordinator, not Spring State Machine |
| [003](adr/003-workflow-vs-agent-for-payment-decisioning.md) | Multi-agent fan-out for payment decisioning |
| [004](adr/004-a2a-discovery-only.md) | A2A discovery endpoint only — no JSON-RPC surface |
| [005](adr/005-one-mcp-server.md) | One MCP server (sanctions lookup) |
| [006](adr/006-virtual-threads-no-preview.md) | Virtual threads + CompletableFuture, no StructuredTaskScope |
| [007](adr/007-model-routing.md) | Haiku for routing, Sonnet for risk + compliance, no Opus |
| [008](adr/008-evals-as-ci-gate.md) | Eval regression fails CI |
| [009](adr/009-pgvector-canonical-schema.md) | Spring AI canonical pgvector schema for `route_metrics` |
| [010](adr/010-transactional-outbox-vs-chained-kafka-tx-manager.md) | Transactional outbox for atomic state + event |

## What is *not* in this picture

Out of scope per `REQUIREMENTS.md §4.2` and the ADRs above. The most architecturally significant
omissions:

- **No full A2A protocol.** Discovery endpoint only — see ADR-004.
- **No reactive Spring.** Servlet stack everywhere. The decision plane uses virtual threads on the
  servlet container — not WebFlux. ADR-001.
- **No Spring State Machine.** ADR-002.
- **No Opus.** Sonnet + Haiku only. ADR-007.
- **No real PSP, no real sanctions feed.** Mocks under `services/mock-psp/` and
  `services/sanctions-mcp/`.

Carry-over and known limitations: `docs/known-issues.md`.
