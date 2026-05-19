# CLAUDE.md

**Actually run the affected test target.** Not "compile passes", not "build -x test". Run the tests. If they need Docker that isn't available, say so explicitly in the summary — don't substitute a lesser verification and call it done. Skipping tests is acceptable only with named justification (Docker absent, network required, API key missing).
Read on every session. Keep this file short. If a rule isn't here, you don't have it.

## 1. Mission

AgentPay Gateway is a pet/portfolio project: an agent-native payment gateway with a multi-agent decision plane. **The architecture is the artifact, not a production system.** Scope is bounded by `REQUIREMENTS.md` — everything else is roadmap.

## 2. Sources of truth (in order)

1. `REQUIREMENTS.md` — every requirement has an ID (`FR-G-001`, `NFR-S-007`) and acceptance criteria. **The contract.**
2. `README.md` — architecture, rationale, ADR index. The *why*.
3. `docs/adr/*.md` — architectural decisions with trade-offs.
4. `CLAUDE_CODE_PLAYBOOK.md` — iteration plan. One session = one iteration.

**Conflicts:** `REQUIREMENTS.md` wins. **Ambiguity:** pick the simpler interpretation, note the assumption in a code comment, continue.

## 3. Working principles (apply to every change)

### Think before coding
- State assumptions explicitly before you write code. If two interpretations exist with different effort costs — stop and ask.
- If something is unclear, name what's confusing. Don't paper over it with a guess.
- Before any non-trivial change, write a short plan: `1. step → verify: check`. Use plan mode (Shift+Tab twice) for anything spanning ≥3 files.

### Simplicity first
- Minimum code that solves the problem. No speculative abstractions, no "flexibility" not in `REQUIREMENTS.md`.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it. Ask: *"Would a senior engineer say this is overcomplicated?"*

### Surgical changes
- Touch only what the task requires. Don't reformat, don't refactor adjacent code, don't "improve" comments.
- Match existing style, even if you'd write it differently.
- If you notice unrelated dead code or a bug — mention it in chat, don't fix it silently.
- Every changed line must trace directly to the user's request or a requirement ID.

### Goal-driven execution
- Turn vague asks into verifiable goals: *"add validation"* → *"write tests for invalid inputs, then make them pass"*.
- Test-first for every requirement with an acceptance criterion. Watch it fail, then implement.
- Acceptance scenarios in `REQUIREMENTS.md` §10 are end-to-end JUnit 5 + Testcontainers tests.

## 4. Stack policy — non-negotiable

- **Java 21 LTS only.** No preview features. No `--enable-preview`. No primitive patterns. No `StructuredTaskScope`.
- **Spring Boot 3.5.x only.** Not 4.0, not 3.4. Pinned in `gradle/libs.versions.toml`.
- **Spring AI 1.1.5 only.** Not 1.0.x, not 2.0-M anything. Pinned.
- **No `-M`, `-RC`, `-SNAPSHOT` versions** anywhere in the build.
- **Pin everything** via `gradle/libs.versions.toml`. No floating versions.
- **No `@Experimental` APIs** even from stable libraries.
- **Claude models:** Sonnet 4.6 + Haiku 4.5 only. No Opus.

If a feature requires a newer version, **the answer is not to upgrade**. The answer is: leave it for `docs/roadmap.md` and use a stable alternative. See ADR-001.

## 5. Pre-commit checklist (mandatory, every commit)

```bash
# 1. Affected tests pass
./gradlew :<module>:test

# 2. No unstable deps — MUST return nothing
./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"

# 3. No preview flags anywhere — MUST return nothing
grep -rn "enable-preview" build.gradle.kts services/

# 4. Formatting
./gradlew spotlessApply
```

If you have hooks configured in `.claude/settings.json`, these run automatically. Don't bypass them.

## 6. Scope discipline

- Implement **only what the current iteration asks for**. Don't pre-build for future iterations.
- Don't add features not in `REQUIREMENTS.md`. If useful → append to `docs/roadmap.md` instead.
- Match the file paths in `REQUIREMENTS.md` §11 **exactly**. Do not invent alternative module layouts.
- One commit per logical change. Conventional Commits: `feat(gateway): issue intent tokens (FR-G-001)`.

## 7. Stop conditions

Stop and ask the user when:
- A requirement is materially ambiguous and the two readings have different effort costs.
- An iteration's stop condition (per `CLAUDE_CODE_PLAYBOOK.md`) is reached. **Do not start the next iteration in the same session.**
- A single error has resisted three attempts — print the exact error, stack trace, failing assertion line, and your three best hypotheses. Wait.
- The deliverables checklist in `REQUIREMENTS.md` §11 needs an update but work is uncommitted.

## 8. Stack notes (gotchas worth keeping)

### Java 21 LTS
- Virtual threads are stable since Sept 2023 — no preview flag needed.
- For concurrent fan-out: `Executors.newVirtualThreadPerTaskExecutor()` returns a standard `ExecutorService`. Use `CompletableFuture.supplyAsync(task, vtExecutor)` + `CompletableFuture.allOf(...)`.
- Always shut down virtual-thread executors via `@PreDestroy` on the owning bean.
- **Do NOT use `StructuredTaskScope`** — preview through Java 25.

### Spring Boot 3.5.x
- Servlet stack (not reactive) unless a requirement explicitly demands reactive. Spring AI 1.1's primary documented path is blocking.
- Records for DTOs. No Lombok.
- `spring-boot-starter-web` (servlet) + `spring-boot-starter-actuator`.
- JWT: `spring-boot-starter-oauth2-resource-server` for verification, `nimbus-jose-jwt` for signing. Don't roll your own.

### Spring AI 1.1.5
- Use the `ChatClient` fluent API, **not `ChatModel` directly**.
- Structured outputs via `.entity(SomeRecord.class)`. Records with `@JsonProperty` annotations.
- Anthropic provider: `spring-ai-starter-model-anthropic`. Key via `spring.ai.anthropic.api-key`.
- MCP server (servlet): `spring-ai-starter-mcp-server-webmvc`. Annotate tool methods with `@McpTool` and parameters with `@McpToolParam(description=..., required=true)`. The description is what the model sees — write it like a tool spec.
- **Gotcha (confirmed in 1.1.5):** `@McpTool` and `@McpToolParam` are NOT in `org.springframework.ai.*`. They live in `org.springaicommunity.mcp.annotation.McpTool` / `McpToolParam` (from `mcp-annotations:0.8.0`, pulled transitively by the starter). The auto-configuration scans `@McpTool` methods on any Spring bean and registers them as MCP tools automatically — no additional wiring needed.
- MCP client (servlet): `spring-ai-starter-mcp-client`. Configure via `spring.ai.mcp.client.streamable-http.connections.<name>.url`.
- Advisors are the right place for PII redaction. Implement as an `Advisor` that mutates request `messages` before send.
- Spring AI 1.1 emits Micrometer chat-model observability natively. Add `micrometer-tracing-bridge-otel` + OTLP exporter — no manual LLM instrumentation needed.
- **Gotcha:** calling both `.chatResponse()` and `.entity()` on the same `CallResponseSpec` may issue two API calls. Pick one; if you need both, capture the raw response and parse it yourself.

### Anthropic model IDs (use exactly these)
- Sonnet (reasoning): `claude-sonnet-4-6`
- Haiku (classification): `claude-haiku-4-5-20251001` (dated snapshot — pin in production-grade code)
- **No Opus.** Sonnet 4.6 handles all reasoning paths. See ADR-007.

### A2A — discovery only
- Spring AI A2A starter requires Spring Boot 4.0 + Spring AI 2.0 (out of stack policy).
- Implementation: one Spring MVC controller returning a constant `AgentCard` JSON at `GET /.well-known/agent.json`. ~30 lines. See ADR-004.

### Postgres 16 + pgvector
- Use Spring AI's `PGVectorStore` (`spring-ai-pgvector-store`).
- Flyway migrations: `src/main/resources/db/migration/V<n>__<name>.sql`.
- Postgres 16 LTS, pgvector 0.7.x. The docker image needs `CREATE EXTENSION vector` via init script.

### Kafka
- `apache/kafka:3.7.x` in KRaft mode (no ZooKeeper).
- Spring Kafka, **transactional producer for state-transition events** (NFR-R-003).
- Avro schemas in `shared/api-contracts/avro/`.

### Observability
- Micrometer Tracing → OTLP via `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` → local `otel-collector` → Langfuse + Prometheus.
- Spring AI emits GenAI telemetry automatically. **Don't manually instrument LLM calls.**

## 9. Anti-patterns — do not do these

- Reactive Spring (WebFlux/Mono/Flux) unless a requirement demands it.
- Lombok. Use records and explicit getters.
- Hand-rolled JWT libraries.
- MCP from scratch — use Spring AI 1.1 MCP starters.
- A2A beyond the discovery endpoint.
- `StructuredTaskScope`, primitive patterns, any Java preview features.
- Any Spring AI 2.0 features (A2A starter, `OrchestratorWorkersWorkflow`, etc.).
- Claude Opus models.
- Spring State Machine. The Saga is one explicit coordinator service.
- Silently expanding scope. If unsure → stop and ask.
- Committing secrets, `.env` files, or generated signing keys.
- Dependency-management plugins that pull non-pinned versions.

## 10. When stuck

1. Re-read the relevant section of `REQUIREMENTS.md`. Has the acceptance criterion shifted your understanding?
2. Is there a test you can write right now that would clarify the question?
3. Check `docs/adr/` for a related decision.
4. Check the Spring AI 1.1 docs: `https://docs.spring.io/spring-ai/reference/1.1/`. If you land on a different version's page, navigate back. **Source code wins over outdated docs** — search `spring-projects/spring-ai` on the 1.1.x branch.
5. Still unclear? **Ask the user.** State the ambiguity, list the two interpretations, recommend the simpler one. Do not guess.

## 11. Useful commands

```bash
make up              # bring up the stack
make demo            # happy path + compensation
make test            # all tests
make eval            # evals only
make logs SERVICE=orchestrator
./gradlew :services:orchestrator:test
./gradlew dependencies | grep -E "M[0-9]+$|RC|SNAPSHOT"   # must be empty
./gradlew spotlessApply
```

## 12. Optional: `.claude/` directory

This repo may ship a `.claude/` directory with:
- `settings.json` — hooks (PreToolUse / PostToolUse / Stop) enforcing §5 deterministically.
- `skills/` — on-demand domain knowledge (Saga states, ISO 20022 codes, threat-model snippets). Loaded only when relevant.
- `agents/` — specialised subagents (e.g. `test-writer`, `adr-author`, `spring-ai-reviewer`).

**These are advisory infrastructure, not part of `REQUIREMENTS.md`.** If they exist, follow them. If they don't, follow this file. Do not create them speculatively.
