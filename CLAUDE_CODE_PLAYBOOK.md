# Claude Code Playbook for AgentPay Gateway

> A sequence of bounded iterations, each with a copy-pasteable prompt. One iteration per Claude Code session. Goal: build the MVP from `REQUIREMENTS.md` without losing scope discipline or stack stability.

---

## How to use this playbook

### One-time setup

1. Create a new Git repository.
2. Place these four files at the repo root:
   - `README.md`
   - `REQUIREMENTS.md`
   - `CLAUDE.md`
   - `CLAUDE_CODE_PLAYBOOK.md` (this file)
3. Initial commit: `git commit -m "chore: project docs"`.
4. Set up your environment:
   - **Java 21 LTS** JDK installed (`java -version` should show `21.x.x`, NOT 22, NOT 25). No preview flags ever.
   - Docker Desktop running.
   - `export ANTHROPIC_API_KEY=sk-ant-...` in your shell.
5. Install Claude Code: see [official docs](https://docs.claude.com/en/docs/claude-code).
6. `cd` to your repo root.

### Per-iteration flow

For each iteration:

1. Start a fresh Claude Code session: `claude` (or `/clear` if already running).
2. Copy the iteration's prompt from this playbook.
3. Paste it. Let Claude Code work.
4. When Claude Code stops at the iteration's stop condition, review the diff.
5. Run the iteration's verification commands. **Always** verify no unstable deps slipped in:
   ```bash
   ./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"   # must be empty
   grep -rn "enable-preview" build.gradle.kts services/      # must be empty
   ```
6. If verification green: commit. If red: paste the recovery prompt for that iteration.
7. Move to the next iteration. **Always `/clear` between iterations.**

### Discipline rules

- **One iteration per session.** Do not let Claude Code continue beyond an iteration's stop condition in the same context window.
- **Always run tests before committing.** The verification commands at the end of each iteration are non-negotiable.
- **Update `REQUIREMENTS.md` §11 checklist** at the end of each iteration. Claude Code does this automatically if the prompt is followed.
- **If Claude Code goes off-script, stop and ask it to re-read `CLAUDE.md`.**
- **If Claude Code suggests upgrading to Spring AI 2.0 / Spring Boot 4 / Java 25 / preview features / Opus — refuse.** That's stack-policy violation. See `CLAUDE.md` "Stack policy — non-negotiable".

---

## Iteration 0 — Project skeleton + infrastructure

**Goal:** A Gradle multi-module project with empty service modules, a working `docker-compose.yml` for infra, a `Makefile`, and a `.github/workflows/ci.yml` stub. No application logic yet.

### Prompt

```
We are starting Iteration 0 of the AgentPay Gateway project.

Read in this order:
1. CLAUDE.md — especially the "Stack policy — non-negotiable" section.
2. README.md
3. REQUIREMENTS.md (full document — pay particular attention to §11 deliverables, §9 configuration with pinned versions table)

Then implement Iteration 0 with the following scope:

CREATE:
- Gradle multi-module structure: settings.gradle.kts, root build.gradle.kts (Java 21 toolchain, Kotlin DSL, plugins applied conditionally).
- gradle/libs.versions.toml — version catalog with EXACTLY these pinned versions from REQUIREMENTS.md §9:
    java = "21"
    spring-boot = "3.5.4"
    spring-ai = "1.1.5"
    postgres-driver = "42.7.4"
    pgvector = "0.1.6"
    flyway = "10.20.1"
    nimbus-jose-jwt = "9.40"
    testcontainers = "1.20.4"
    bucket4j = "8.10.1"
  No floating versions. No -M, -RC, or -SNAPSHOT anywhere.
- Modules per REQUIREMENTS.md §11.1:
  * services/gateway
  * services/orchestrator
  * services/sanctions-mcp
  * services/mock-psp
  * services/buyer-client
  * shared/api-contracts
  * evals
- Each service module: minimal Spring Boot 3.5 application class, application.yml with placeholder config, one health endpoint, one trivial unit test that asserts the context loads. Use spring-boot-starter-web (servlet, NOT WebFlux) and spring-boot-starter-actuator.
- docker-compose.yml with infra services using EXACTLY these pinned images:
    postgres:16-alpine with pgvector extension (init script creates the extension)
    redis:7-alpine
    apache/kafka:3.7.x in KRaft mode (no ZooKeeper)
    langfuse/langfuse:3
    otel/opentelemetry-collector-contrib:0.110.0
    prom/prometheus:v2.55.0
    grafana/grafana:11.3.0
- Makefile with targets: up, down, demo, test, eval, logs, langfuse, grafana, trace. Most targets can be stubs at this stage (echo "not implemented yet"). The `up` and `down` targets MUST work end-to-end with the infra services.
- .github/workflows/ci.yml with three jobs (build, test, eval) — stub jobs that just echo for now.
- .gitignore: standard Java + Gradle + IDE + .env + .local
- .env.example with all variables from REQUIREMENTS.md §9.

CRITICAL CONSTRAINTS:
- No --enable-preview anywhere in compile or test tasks.
- No StructuredTaskScope, no primitive patterns, no preview features.
- No reactive Spring (no WebFlux, no Mono, no Flux). Servlet stack only.
- No Lombok.
- All dependencies routed through libs.versions.toml.

DO NOT:
- Write any business logic.
- Add JWT/MCP/Spring AI dependencies yet — those come in later iterations.
- Create Flyway migrations yet.
- Use any Spring AI 2.0 features. We are on 1.1.5.

VERIFICATION (run all three; all must succeed):
1. `./gradlew build` succeeds.
2. `./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"` returns nothing.
3. `make up` brings the infra stack up; `docker compose ps` shows all services healthy; `make down` tears it down cleanly.

STOP CONDITION:
- All of the above is committed in logical commits (Conventional Commits style).
- The deliverables checklist in REQUIREMENTS.md §11.3 has the relevant items checked off.
- Print a final summary listing what was created and confirming all three verification commands passed.

Stop after the stop condition. Do not start Iteration 1.
```

### Recovery prompt (if verification fails)

```
Verification failed at step <X>. Output:

<paste error>

Re-read CLAUDE.md "Stack policy — non-negotiable" section. Fix the failing step only. Do not expand scope. Commit the fix.
```

---

## Iteration 1 — Mock PSP + Sanctions MCP server

**Goal:** Two leaf services with no LLM dependencies. Pure Spring Boot. Implementing them first means later iterations have real downstream services to call.

### Prompt

```
We are starting Iteration 1.

Read in this order:
1. CLAUDE.md
2. REQUIREMENTS.md §5.5 (Sanctions MCP) and §5.6 (Mock PSP), §7.3 (MCP tool contract).
3. README.md sections covering the MCP boundary and the mock PSP.
4. The Spring AI 1.1 MCP server documentation at https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-server-boot-starter-docs.html

Then implement Iteration 1.

SANCTIONS MCP SERVER (services/sanctions-mcp):
- Implement FR-M-001 through FR-M-006.
- Dependency: spring-ai-starter-mcp-server-webmvc (servlet-based, synchronous variant — the simplest stable path).
- Expose one tool annotated with @McpTool:
    @McpTool(description = "Look up a person or entity name against synthetic sanctions/PEP fixture data.")
    public SanctionsResult lookup_sanctions(
        @McpToolParam(description = "Full name", required = true) String name,
        @McpToolParam(description = "ISO 3166-1 alpha-2 country code") String country) { ... }
- SanctionsResult as a Java record per §7.3.
- Fixture data at services/sanctions-mcp/src/main/resources/fixtures/sanctions.json with at least 20 obviously-synthetic entries.
- Matching: case-insensitive, tolerate one Levenshtein edit for entries > 5 chars (write your own simple Levenshtein; do NOT add a library dependency for this).
- Service name in docker-compose: sanctions-mcp, port 8090.
- Unit tests for matching logic (exact, case-insensitive, fuzzy).
- Integration test using Testcontainers: start the server, invoke lookup_sanctions via the MCP client SDK (spring-ai-starter-mcp-client), assert behavior for a match and a non-match.

MOCK PSP (services/mock-psp):
- Implement FR-P-001 through FR-P-003.
- POST /charge accepting ChargeRequest, returning ChargeResponse.
- Three configurable PSP profiles with deterministic outcomes seeded by case_id.
- Emit ISO 20022 reason codes (AC01, AM04, DT03) on failure.
- Service name in docker-compose: mock-psp, port 8091.
- Unit tests for determinism: same case_id → same outcome.
- Integration test: 100 cases hit each profile, success rate matches profile within ±2%.

UPDATE:
- docker-compose.yml to include both services with healthchecks.
- Update REQUIREMENTS.md §11.3 checklist.

CRITICAL CONSTRAINTS:
- All Spring AI deps via the version catalog, pinned to 1.1.5.
- No reactive variants of any starter.
- No --enable-preview.

VERIFICATION:
1. `./gradlew :services:sanctions-mcp:test :services:mock-psp:test` all pass.
2. `./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"` returns nothing.
3. `make up` brings both up healthy.
4. Manually: `curl localhost:8091/charge -d '{"case_id":"test","amount":10,"currency":"USD","psp_id":"psp-a"}' -H 'Content-Type: application/json'` returns a deterministic response.

STOP CONDITION:
- Above is committed in logical commits.
- Checklist updated.
- Print a summary including a sample MCP tool invocation log and a sample PSP charge response.

Stop after the stop condition.
```

### Recovery prompt

If MCP server doesn't expose tools correctly:

```
The MCP server doesn't expose lookup_sanctions correctly. The MCP client cannot discover the tool. Check:
1. The @McpTool annotation is on a public method of a Spring @Service bean.
2. The starter is spring-ai-starter-mcp-server-webmvc (not -webflux, not without -webmvc).
3. spring.ai.mcp.server.type=SYNC in application.yml (default, but worth checking).
4. spring.ai.mcp.server.capabilities.tool=true (default true).
5. The @McpToolParam annotations are present on parameters.

Re-read https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-server-boot-starter-docs.html. Fix only this. Do not change library versions.
```

---

## Iteration 2 — Gateway (intent tokens + A2A discovery + PII redaction)

**Goal:** The externally reachable gateway. JWT intent tokens, A2A discovery endpoint (static AgentCard JSON), PII redaction filter, rate limiting. Acceptance scenarios D and E end-to-end.

### Prompt

```
We are starting Iteration 2.

Read in this order:
1. CLAUDE.md
2. REQUIREMENTS.md §5.1, §6.3 (security NFRs), §7.1 (API contracts including 7.1.4 AgentCard), §10.4 (Scenario D), §10.5 (Scenario E).
3. README.md sections on the agent-native API gateway.

Then implement Iteration 2 (services/gateway).

DEPENDENCIES TO ADD (all routed through gradle/libs.versions.toml):
- spring-boot-starter-web
- spring-boot-starter-actuator
- spring-boot-starter-security
- spring-boot-starter-oauth2-resource-server
- spring-boot-starter-data-redis
- com.nimbusds:nimbus-jose-jwt
- com.bucket4j:bucket4j-core (and bucket4j-redis for distributed rate limiting)

DO NOT add Spring AI A2A starter. It requires Spring Boot 4 + Spring AI 2.0 which violates our stack policy. We implement A2A discovery manually as one static controller (see below).

IMPLEMENT:
- POST /intent-tokens (FR-G-001, FR-G-002): IntentTokenRequest → JWT per §7.1.1.
- Signing-key bootstrap: if GATEWAY_SIGNING_KEY_PEM env var is absent, generate an RSA-2048 keypair to .local/gateway-key.pem on startup (NFR-S-002). Log a warning. .local is in .gitignore.
- GET /.well-known/jwks.json: publish the public key as a JWKS using nimbus-jose-jwt's JWKSet.toJSONObject().
- POST /payments (FR-G-003, FR-G-004): validate intent token, enforce scope (amount cap, audience, expiry, replay via Redis jti store), forward to orchestrator. For this iteration the orchestrator forwarding can be stubbed (log only — real wiring in Iteration 3).
- GET /.well-known/agent.json (FR-G-005): A2A discovery card. Implement as a single @RestController returning a constant record/POJO matching the schema in §7.1.4. About 30 lines total. No SDK dependency.
- PII redaction component (FR-G-006, NFR-S-003): a Servlet filter (or a request-body transformer) that masks PAN and IBAN. This same component will be reused as a Spring AI Advisor in the orchestrator later — write it now in a way that allows that reuse (e.g., a static utility class that the filter and the advisor both call).
- Per-agent rate limiting at 60 rpm (FR-G-007): Bucket4j with Redis-backed sliding window.
- /actuator/prometheus exposed (FR-G-008).

TESTS:
- Unit: token issuance, claim validation, signature verification, PII redaction (cover at least PAN and IBAN).
- Integration (Testcontainers: Redis): Scenario D (scope amount exceeded) and Scenario E (jti replay).
- Integration: rate limiter — 61st request in 60s returns HTTP 429.
- Integration: GET /.well-known/agent.json returns a valid JSON document with the expected skills.

UPDATE:
- docker-compose.yml: add gateway service, port 8080, depends_on: redis (healthy).
- application.yml with the keys from REQUIREMENTS.md §9 relevant to the gateway.
- REQUIREMENTS.md §11 checklist.

DO NOT:
- Implement the orchestrator forwarding yet — log "WOULD FORWARD" and return HTTP 202 with a generated case_id.
- Implement A2A protocol request handling (JSON-RPC sendMessage etc.) — discovery endpoint only.
- Add any spring-ai-* dependency. The gateway has no LLM calls.

VERIFICATION:
1. `./gradlew :services:gateway:test` green.
2. `./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"` returns nothing.
3. Scenario D and Scenario E integration tests pass.
4. `make up` brings gateway up healthy.
5. `curl localhost:8080/.well-known/jwks.json` returns a JWKS.
6. `curl localhost:8080/.well-known/agent.json` returns a valid AgentCard.

STOP CONDITION:
- Committed in logical commits referencing FR IDs.
- Checklist updated.
- Summary printed.

Stop after the stop condition.
```

---

## Iteration 3 — Orchestrator skeleton + Saga + crash recovery

**Goal:** Orchestrator service with a real Saga coordinator, Postgres persistence, Kafka publishing, idempotency. The supervisor is a temporary stub that returns `APPROVED` so we can run Scenario A end-to-end and Scenario G (crash recovery). Real agents arrive in Iteration 4.

### Prompt

```
We are starting Iteration 3.

Read in this order:
1. CLAUDE.md
2. REQUIREMENTS.md §5.2 (orchestrator), §6.4 (reliability NFRs), §7.2 (decision plane contracts), §7.4 (Kafka topics), §8 (data model), §10.1 (Scenario A), §10.7 (Scenario G).
3. README.md sections on the orchestrator and the Saga.

Then implement Iteration 3 (services/orchestrator).

DEPENDENCIES (all pinned via libs.versions.toml):
- spring-boot-starter-web (servlet, NOT WebFlux)
- spring-boot-starter-data-jpa
- spring-boot-starter-actuator
- spring-kafka (with transactional support)
- flyway-core, flyway-database-postgresql
- postgresql JDBC driver

DO NOT add any spring-ai-* dependencies in this iteration. The supervisor is a stub here; real Spring AI integration is Iteration 4.

IMPLEMENT:
- Flyway migration V1__init.sql: cases, saga_transitions, agent_verdicts, route_metrics tables per REQUIREMENTS.md §8.
- Saga states enum and PaymentSaga coordinator service per FR-O-001 — FR-O-009.
- State persistence transactional with state-change actions (FR-O-002).
- Idempotency on case_id (FR-O-007).
- Kafka topics created on startup if absent. Avro schemas in shared/api-contracts/avro/. Use a stable Gradle Avro plugin pinned in libs.versions.toml.
- Transactional Kafka producer (NFR-R-003).
- Stub Supervisor service that returns Decision.APPROVED with a hardcoded route. Mark with TODO referencing Iteration 4.
- Endpoint POST /internal/payments accepting the gateway's forwarded payload, creating a case, running the Saga.
- Wire the gateway to call this endpoint (replace the "WOULD FORWARD" log from Iteration 2).
- Crash-recovery on startup: scan cases in non-terminal states and resume their Sagas (FR-O-002, NFR-R-002). Implement as an ApplicationRunner that queries cases WHERE state NOT IN (terminal states) and resumes each.

TESTS:
- Unit: state-machine transitions, idempotency, compensation order.
- Integration (Testcontainers: Postgres + Kafka): Scenario A end-to-end through the stub supervisor.
- Integration: Scenario G — kill the orchestrator process mid-Saga (use Testcontainers' container.execInContainer to SIGKILL the JVM, then restart), assert the Saga reaches a terminal state.

UPDATE:
- docker-compose.yml: add orchestrator service, depends_on: postgres, kafka.
- REQUIREMENTS.md §11 checklist.

DO NOT:
- Implement real agents. The supervisor is a stub returning APPROVED.
- Implement budget enforcement (Iteration 6) or observability beyond basic logging (Iteration 6).
- Use Spring State Machine. This is an explicit Saga coordinator @Service.

VERIFICATION:
1. `./gradlew :services:orchestrator:test` green, Scenarios A (stub) and G pass.
2. `./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"` returns nothing.
3. `make demo` runs a payment end-to-end via stub supervisor and reaches COMMITTED.

STOP CONDITION:
- Committed.
- Checklist updated.
- Summary printed including final Saga state for a demo case.

Stop after the stop condition.
```

---

## Iteration 4 — Decision plane: supervisor + 3 specialist agents

**Goal:** Replace the stub supervisor with the real manual supervisor that fans out to RiskAgent, ComplianceAgent, RoutingAgent. Scenarios B (compliance fail → compensation) and C (review → human approval) end-to-end.

### Prompt

```
We are starting Iteration 4 — the most substantive iteration.

Read in this order:
1. CLAUDE.md — re-read the "Stack notes" section on Spring AI 1.1.5 specifically.
2. REQUIREMENTS.md §5.3 (supervisor), §5.4 (agents), §10.2 (Scenario B), §10.3 (Scenario C).
3. README.md "The agents" table and the architectural lineage section.
4. The Spring AI 1.1 ChatClient documentation at https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html
5. The Spring AI 1.1 structured-output documentation at https://docs.spring.io/spring-ai/reference/1.1/api/structured-output-converter.html
6. The Spring AI 1.1 MCP client documentation at https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-client-boot-starter-docs.html

Then implement Iteration 4 (services/orchestrator).

DEPENDENCIES TO ADD (all pinned to spring-ai = 1.1.5 in libs.versions.toml):
- spring-ai-starter-model-anthropic
- spring-ai-pgvector-store
- spring-ai-starter-mcp-client
- spring-ai-advisors-vector-store (if needed for RAG)

DO NOT add any Spring AI 2.0 or 2.0-M dependency. DO NOT add spring-ai-community/spring-ai-a2a. We do NOT use OrchestratorWorkersWorkflow (that's Spring AI 2.0 only) — we write the supervisor manually as a plain @Service.

IMPLEMENT:

Three prompt files under services/orchestrator/src/main/resources/prompts/:
  * risk.md — system prompt for RiskAgent, with negative space declaration in the first paragraph.
  * compliance.md — same for ComplianceAgent.
  * routing.md — same for RoutingAgent.
  Each MUST include 1-2 worked few-shot examples for the most common decision class.

Java records for structured outputs (in com.agentpay.orchestrator.domain):
  * RiskAssessment(int score, List<String> signals, String rationale)
  * ComplianceVerdict(Outcome outcome, List<String> citations, String rationale) — Outcome enum: PASS, FAIL, REVIEW.
  * RouteRecommendation(String pspId, String routeId, float expectedSuccessRate, int expectedCostBps, String rationale)
  * Decision(DecisionOutcome outcome, int riskScore, ComplianceVerdict compliance, Optional<RouteRecommendation> route, List<String> rationale) — DecisionOutcome enum: APPROVED, DECLINED, REVIEW.

Three @Service classes: RiskAgent, ComplianceAgent, RoutingAgent. Each builds its own ChatClient configured with the appropriate model from application.yml:
  - RiskAgent — claude-sonnet-4-6
  - ComplianceAgent — claude-sonnet-4-6
  - RoutingAgent — claude-haiku-4-5

Each agent uses ChatClient's structured output binding:
    var result = chatClient.prompt()
        .system(systemPrompt)
        .user(userPrompt)
        .call()
        .entity(RiskAssessment.class);

MCP client wiring: ComplianceAgent gets the ToolCallbackProvider from spring-ai-starter-mcp-client and attaches it via .toolCallbacks(provider) on its ChatClient. Configure the connection in application.yml:
    spring.ai.mcp.client.streamable-http.connections.sanctions.url: http://sanctions-mcp:8090

pgvector seed: routing-metrics fixture loaded on first startup via an ApplicationRunner.

REAL SUPERVISOR — manual implementation:

@Service
public class Supervisor {
    private final RiskAgent risk;
    private final ComplianceAgent compliance;
    private final RoutingAgent routing;
    private final ExecutorService executor;

    public Supervisor(RiskAgent risk, ComplianceAgent compliance, RoutingAgent routing) {
        this.risk = risk;
        this.compliance = compliance;
        this.routing = routing;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public Decision decide(PaymentContext ctx) {
        var riskFut = CompletableFuture.supplyAsync(() -> risk.assess(ctx), executor)
            .orTimeout(10, TimeUnit.SECONDS)
            .exceptionally(e -> RiskAssessment.review("timeout: " + e.getMessage()));
        var complianceFut = CompletableFuture.supplyAsync(() -> compliance.check(ctx), executor)
            .orTimeout(10, TimeUnit.SECONDS)
            .exceptionally(e -> ComplianceVerdict.review("timeout: " + e.getMessage()));
        var routingFut = CompletableFuture.supplyAsync(() -> routing.route(ctx), executor)
            .orTimeout(10, TimeUnit.SECONDS)
            .exceptionally(e -> null);
        CompletableFuture.allOf(riskFut, complianceFut, routingFut).join();
        return aggregate(riskFut.join(), complianceFut.join(), routingFut.join());
    }

    private Decision aggregate(...) { /* per FR-DP-002 rule */ }

    @PreDestroy
    void shutdown() { executor.shutdown(); }
}

PII redaction Advisor:
- Implement as a Spring AI Advisor (implements CallAdvisor / StreamAdvisor depending on Spring AI 1.1 API surface — check the docs).
- Reuses the redaction utility from Iteration 2.
- Applied to ALL three agents' ChatClients via .defaultAdvisors(...).

Per-specialist timeout 10s → treated as REVIEW (FR-DP-003) — already in the supervisor code above.

Structured output validation retry: if ChatClient.entity() throws a parse exception, retry once with the same prompt; if the second attempt fails, return a REVIEW verdict for that specialist (NFR-Q-002).

Persist agent_verdicts on every call (model, tokens, latency, cost) — use Spring AI's Micrometer metrics for token counts.

TESTS:
- Unit: aggregation rule covers all branches (APPROVED, DECLINED-by-compliance, DECLINED-by-risk, REVIEW-by-risk, REVIEW-by-compliance, REVIEW-by-timeout).
- Unit: per-agent prompt-rendering snapshot tests (assert the negative space block is present in the rendered system message).
- Integration (Testcontainers: Postgres + Kafka + the sanctions-mcp container): Scenarios B and C end-to-end.
- Integration: specialist timeout → REVIEW (mock the agent to sleep > 10s).

UPDATE:
- application.yml: agentpay.models block per REQUIREMENTS.md §9.
- REQUIREMENTS.md §11 checklist.

CRITICAL CONSTRAINTS:
- No StructuredTaskScope. Use Executors.newVirtualThreadPerTaskExecutor() + CompletableFuture. Both fully stable in Java 21.
- No OrchestratorWorkersWorkflow. Write the supervisor manually as shown.
- No Opus models. Only Sonnet 4.6 and Haiku 4.5.
- No --enable-preview.

VERIFICATION:
1. `./gradlew :services:orchestrator:test` green; Scenarios A (now via real agents), B, C all pass.
2. `./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"` returns nothing.
3. `make demo` runs happy path AND compliance-fail path back to back.

STOP CONDITION:
- Committed.
- Checklist updated.
- Summary including token usage and cost for the two demo runs.

Stop after the stop condition.
```

### Recovery prompts

If structured outputs aren't parsing reliably:

```
Structured output parsing is failing intermittently. Common causes:
1. The record class has fields the model isn't generating reliably — simplify the record.
2. The system prompt does not strongly constrain the output format — add an explicit "Output ONLY valid JSON matching this schema" instruction.
3. The model is Haiku 4.5 and the task is too complex — keep using Sonnet 4.6 for compliance/risk; only routing uses Haiku.

DO NOT switch to Opus. Stack policy is Sonnet 4.6 + Haiku 4.5 only.

Diagnose by logging the raw response. Apply the smallest fix. Re-run the integration test.
```

If MCP client cannot reach the sanctions server:

```
The MCP client is not reaching sanctions-mcp. Check:
1. application.yml has spring.ai.mcp.client.streamable-http.connections.sanctions.url pointing to http://sanctions-mcp:8090 (docker service name, not localhost).
2. The orchestrator container is on the same docker network as sanctions-mcp.
3. The sanctions-mcp container is healthy (`docker compose ps`).
4. The ToolCallbackProvider bean is injected into ComplianceAgent and attached via .toolCallbacks(provider) on its ChatClient.
5. The connection-type in application.yml matches the server transport — streamable-http maps to spring-ai-starter-mcp-server-webmvc on the server side.

Fix only the connectivity. Do not change library versions.
```

---

## Iteration 5 — Buyer client + end-to-end demo

**Goal:** The CLI buyer agent. `make demo` runs Scenarios A and B end-to-end and prints clickable Langfuse URLs.

### Prompt

```
We are starting Iteration 5.

Read in this order:
1. CLAUDE.md
2. REQUIREMENTS.md §5.7 (buyer client), §10.1 (Scenario A), §10.2 (Scenario B).

Then implement Iteration 5 (services/buyer-client).

DEPENDENCIES:
- spring-boot-starter-web (for the HTTP client — use RestClient, which is the stable replacement for RestTemplate in 3.5)
- com.nimbusds:nimbus-jose-jwt (for signing the payment request)

IMPLEMENT:
- Spring Boot CommandLineRunner CLI per FR-B-001 — FR-B-004.
- Generate or load buyer keypair at ~/.agentpay/buyer-key.pem.
- Request intent token, sign the payment request with the buyer's private key (the signature is over the canonical form of merchant_id + amount + currency + case_id + nonce).
- Submit POST /payments, poll GET /cases/{case_id} until terminal state, print result.
- Print the Langfuse trace URL constructed from the case_id (assume Langfuse URL template configurable via env var).
- Three preset scenarios via --scenario flag:
  * happy: $42 to merchant-acme, name "Alice Buyer", country US
  * compliance-fail: same but name matches a sanctions fixture entry
  * review: payload that yields a risk score in the REVIEW band

UPDATE THE Makefile:
- `make demo`: run happy + compliance-fail sequentially, print both Langfuse URLs.

UPDATE:
- REQUIREMENTS.md §11 checklist.

VERIFICATION:
1. `./gradlew :services:buyer-client:test` green.
2. `./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"` returns nothing.
3. `make demo` runs cleanly, two Langfuse URLs printed, both clickable.
4. Manual: open both URLs in Langfuse UI and verify the traces show the parallel specialist fan-out.

STOP CONDITION:
- Committed.
- Checklist updated.

Stop after the stop condition.
```

---

## Iteration 6 — Observability + evals + cost budget

**Goal:** Full observability stack rendered in Langfuse and Grafana. Evals pipeline with golden cases + two judges. Cost budget circuit breaker (Scenario F).

### Prompt

```
We are starting Iteration 6.

Read in this order:
1. CLAUDE.md
2. REQUIREMENTS.md §5.8 (evals), §6.1-6.2 (performance, observability), §6.6 (cost), §10.6 (Scenario F).
3. The Spring AI 1.1 observability documentation at https://docs.spring.io/spring-ai/reference/1.1/observability/index.html

Then implement Iteration 6.

DEPENDENCIES TO ADD (where missing):
- io.micrometer:micrometer-tracing-bridge-otel
- io.opentelemetry:opentelemetry-exporter-otlp
- spring-boot-starter-actuator (should already be present)

OBSERVABILITY:
- ops/otel-collector/otel-collector-config.yml: receive OTLP from all services, export to Langfuse via OTLP and to a file at /var/log/traces/.
- ops/prometheus/prometheus.yml: scrape all services' /actuator/prometheus.
- ops/grafana/dashboards/agentplane.json: panels per NFR-O-004 (cost-per-case, decision-rate, compensation-rate, specialist-p95, eval-pass-rate).
- All services: enable micrometer-tracing-bridge-otel and confirm OTLP export works.
- Spring AI 1.1 emits chat-model observability via Micrometer natively — verify the metrics appear in /actuator/prometheus without any manual @Observed annotations on LLM calls.
- Compute per-case cost (NFR-O-003) in the orchestrator and attach as span attribute agentpay.case.cost_usd on the root case span.

COST BUDGET (NFR-COST-001, Scenario F):
- After each specialist call returns, check the cumulative case cost against agentpay.budget.per_case_usd.
- If exceeded mid-flight: cancel remaining specialist CompletableFutures (call .cancel(true)), force Decision.outcome = REVIEW, publish `case.budget_exceeded` Kafka event, suspend Saga.

EVALS:
- evals/golden_cases.json: at least 10 cases per FR-E-001. Mix of expected APPROVED, DECLINED, REVIEW. Include at least one PII-redaction check and one scope-violation check.
- evals/src/test/java/: JUnit 5 runners.
  * DeterministicJudge: checks Saga compensation, PII redaction, scope honoring, tool-call assertions.
  * LlmAsJudge: invokes Claude Haiku 4.5 (model: claude-haiku-4-5) via Spring AI ChatClient to grade rationale fields 0-5 with a critique.
- Results written to evals/results/<timestamp>.json + stdout summary.
- Regression threshold: mean_llm_judge_score ≥ 4.0 AND deterministic_pass_rate == 1.0.
- Add `.github/workflows/ci.yml` eval stage that runs `./gradlew :evals:test` and fails on regression.

UPDATE:
- REQUIREMENTS.md §11 checklist.
- README.md "Observability" and "Evals" sections cross-checked against current implementation.

VERIFICATION:
1. `./gradlew :evals:test` runs the eval suite end-to-end.
2. `./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"` returns nothing.
3. `make demo` produces visible traces in Langfuse with per-agent token/cost metrics.
4. Grafana dashboard renders all five panels with non-empty data after a demo run.
5. Scenario F integration test passes.
6. Final summary line of `make eval` shows pass/fail counts and mean judge score.

STOP CONDITION:
- Committed.
- Checklist updated.
- Summary including a copy of the eval-summary line and a Langfuse URL.

Stop after the stop condition.
```

---

## Iteration 7 — Documentation polish: ADRs, threat model, saga diagram

**Goal:** Finalize the architect-facing documentation. Eight ADRs (with the stable-stack framing), the STRIDE threat model, the Saga state diagram, and the extended architecture document.

### Prompt

```
We are starting Iteration 7 — the final iteration.

Read in this order:
1. CLAUDE.md
2. REQUIREMENTS.md §11.4 (documentation deliverables).
3. README.md "Key architectural decisions" table — these are the ADRs to write.

Then create the following files. Use the standard ADR format: Status / Context / Decision / Consequences / Alternatives considered. Each ADR is one page (250-400 words). Date them and number them sequentially.

docs/adr/001-stable-stack-baseline.md
  - Decision: Java 21 LTS + Spring Boot 3.5.x + Spring AI 1.1.5.
  - Context: Spring AI 2.0 GA imminent; Spring Boot 4.0 just released; Java 25 LTS released 8 months ago. None of these have 6+ months of production maturity.
  - Alternatives: Spring AI 2.0 + Spring Boot 4 + Java 25 (rejected — version mismatch risk during pet-project development is unacceptable).
  - Consequences: no Spring AI A2A starter; no StructuredTaskScope; no OrchestratorWorkersWorkflow. Each compensated for elsewhere.

docs/adr/002-saga-coordinator-vs-state-machine.md
  - Decision: Explicit Saga coordinator service.
  - Alternatives: Spring State Machine (rejected for single-Saga scope).
  - When to revisit: introduction of a second Saga (e.g., chargeback evidence).

docs/adr/003-workflow-vs-agent-for-payment-decisioning.md
  - The most important ADR. Cite Anthropic's data point that multi-agent uses ~15× tokens vs single agent.
  - Decision: Use parallel fan-out (Orchestrator-Workers pattern) implemented manually; in a production payment system the same shape would tilt further toward deterministic workflow with LLM only at narrow decision points.
  - Trade-off: token economics vs architectural demonstrativeness. The pet chooses demonstrativeness explicitly.

docs/adr/004-a2a-discovery-only.md
  - Decision: A2A discovery endpoint only (static AgentCard JSON at /.well-known/agent.json).
  - Context: Spring AI A2A starter (spring-ai-community/spring-ai-a2a) requires Spring Boot 4.0+ and Spring AI 2.0.0-M2+, both outside our stack policy (ADR-001).
  - Consequences: full A2A request handling (JSON-RPC sendMessage) deferred to roadmap. Discovery surface alone is sufficient to demonstrate agent-native intent.
  - When to revisit: when Spring Boot 4 / Spring AI 2 reach 6+ months of production maturity.

docs/adr/005-one-mcp-server.md
  - Decision: One MCP server (sanctions lookup) demonstrates the pattern. More servers would be ceremony at this scope.

docs/adr/006-virtual-threads-no-preview.md
  - Decision: Use Executors.newVirtualThreadPerTaskExecutor() + CompletableFuture for parallel fan-out.
  - Alternatives: StructuredTaskScope (rejected — preview through Java 25, violates stack policy ADR-001).
  - Consequences: ~10 lines more code than StructuredTaskScope; no automatic scope-wide cancellation (handled manually via CompletableFuture.cancel). Trade is acceptable.

docs/adr/007-model-routing.md
  - Decision: Haiku 4.5 for routing (classification), Sonnet 4.6 for risk + compliance (reasoning). Opus NOT used.
  - Rationale: Sonnet 4.6 handles all reasoning paths reliably. Adding Opus increases cost and is a third moving part. Logic is in code, not in prompts.
  - When to revisit: if eval scores for risk/compliance fall below threshold despite prompt iteration, consider Opus.

docs/adr/008-evals-as-ci-gate.md
  - Decision: Treat eval regression like any other test failure. Threshold configurable.

docs/threat-model.md
  - One-page STRIDE table covering: prompt injection via merchant metadata, intent-token replay, intent-token scope abuse, MCP lookalike tools, A2A impersonation, token-exhaustion cost attack, Saga state corruption, model output adversarial inputs.
  - For each: Threat / Likelihood / Impact / Mitigation (mapped to NFR-S-XXX IDs).

docs/saga-states.md
  - Mermaid state diagram showing all states from FR-O-001 and the transitions between them.
  - Annotate which transitions trigger compensation.

docs/architecture.md
  - Extended version of the README "Architecture" section.
  - Include both the conceptual Mermaid diagram and a C4 Container diagram.
  - Cross-link all ADRs by ID.

UPDATE:
- REQUIREMENTS.md §11.4 checklist — all items checked.
- README.md ADR table — confirm all linked filenames exist.

VERIFICATION:
1. `ls docs/adr/` shows 8 files numbered 001-008 with the names above.
2. Every ADR has the five-section structure.
3. All cross-references resolve.

STOP CONDITION:
- Committed.
- Final summary listing:
  * Every requirement ID from REQUIREMENTS.md and its implementation status.
  * Definition-of-done checklist (REQUIREMENTS.md §12) — all six items confirmed, including the explicit "No --enable-preview, no -M/-RC/-SNAPSHOT deps anywhere".
- The project is tagged v0.1-mvp.

Stop after the stop condition.
```

---

## Final review prompt (after all iterations)

Once Iteration 7 is committed:

```
Do a final review pass.

Read CLAUDE.md, README.md, and REQUIREMENTS.md (definition of done at §12).

Then verify each of the six Definition-of-Done conditions:
1. From a clean checkout: make up + make demo + make test all succeed.
2. .github/workflows/ci.yml pipeline green on the main branch (run locally via `act` if you have it, or just inspect the YAML for correctness).
3. All items in REQUIREMENTS.md §11 checklist marked [x].
4. Grafana dashboard renders all five panels with data after a demo.
5. Langfuse UI shows parallel specialist fan-out in trace view.
6. No --enable-preview, no -M/-RC/-SNAPSHOT deps anywhere in the build. Verify with:
   ./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"   (must be empty)
   grep -rn "enable-preview" build.gradle.kts services/      (must be empty)

For each: print PASS or FAIL with a one-line justification.

If any fail, propose the single smallest fix (do NOT implement it without my approval).

Stop without modifying any code. This is verification only.
```

---

## Cross-cutting recovery prompts

### When Claude Code drifts off scope

```
You are working outside the scope of Iteration <N>. Re-read CLAUDE.md and the iteration prompt. Revert any out-of-scope changes via `git restore`. Then return to the iteration's explicit task list. If something seems necessary but is not in the iteration, add it as a TODO and continue.
```

### When Claude Code proposes upgrading a dependency

```
You proposed upgrading <X> to version <Y>. Stop. Re-read CLAUDE.md "Stack policy — non-negotiable". The pinned versions in gradle/libs.versions.toml are mandatory. Implement the feature within the constraints of the pinned versions. If the feature genuinely requires a newer version, leave it for roadmap and document why in a code comment. Do not change libs.versions.toml.
```

### When tests are flaky

```
Tests in <module> are flaky. Diagnose root cause:
- Race conditions: missing await/barrier?
- Resource leaks: Testcontainers not cleaned up?
- Time-dependent assertions: replace with deterministic clocks?
- LLM non-determinism in evals: use temperature=0 and a fixed seed where supported?

Apply the smallest fix. Do not retry or use @RetryingTest as a workaround for actual flakiness.
```

### When Spring AI 1.1 documentation is unclear

```
Search the spring-projects/spring-ai GitHub repository for examples matching what we need. Look in the 1.1.x branch (NOT main, which is 2.0). Search the spring-ai-spring-boot-autoconfigure/src/test directory and the spring-ai-docs/src/main/antora/modules/ROOT/pages/ documentation. Mirror the pattern from those examples. If the public docs and the source disagree, the source wins. Do not "fix" by switching to Spring AI 2.0 syntax.
```

### When stuck on a single error

```
Stop iterating. Print the exact error message, the stack trace, the file:line of the failing assertion, and your three best hypotheses for the cause. Do not make further changes. Wait for guidance.
```

---

## Daily-driver tips (not iteration-specific)

- **Always start a session with `/clear`.** Stale context is the #1 cause of Claude Code drift.
- **Pin Claude Code itself to Sonnet 4.6 or higher for this project.** This project's reasoning load benefits from Sonnet-tier minimum.
- **Use `git diff` before every commit.** If the diff includes files outside the current iteration, that's a red flag — discard those changes.
- **Run the version sanity check before every commit:** `./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"` must be empty.
- **Keep CLAUDE.md updated.** When you learn a real gotcha during implementation, add it to the "Stack notes" section.
- **Don't `/clear` in the middle of an iteration.** Finish the current commit first.
- **If something feels like it would be easier with the new version of a framework, that's the wrong instinct.** Stack stability is the point.

---

## Closing

The plan above will produce the v0.1-mvp tag in roughly 35-50 hours. Don't optimize for speed beyond that. The artifact's value is in the architectural clarity of the result, not in shipping faster.

When v0.1-mvp is tagged: record the Loom, push the repo to GitHub, link it from your LinkedIn and CV. That artifact is what changes the technical-interview conversation.
