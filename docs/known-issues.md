# Known issues at v0.1-mvp

> Items not fully working at the v0.1-mvp tag. Each entry: what, why, where it manifests, what
> would be required to close it. Some are pet-project scope limits (resolved as "won't fix");
> others are roadmap items that a hypothetical production fork would need to address.

## 1. RiskAgent has no real tools (FR-A-R-002 unimplemented)

**What.** `FR-A-R-002` mandates two read-only tools on RiskAgent: `velocity_check(agent_id)` and
`fraud_rules_lookup(transaction_pattern)`. Neither is wired. The agent runs **tool-less** today.

**Where.** `services/orchestrator/src/main/java/com/agentpay/orchestrator/agents/RiskAgent.java`
contains a TODO referencing this; the prompt at
`services/orchestrator/src/main/resources/prompts/risk.md` retains tool-call examples that are
effectively rationalization-by-the-model rather than real tool invocations.

**Impact.** Risk scores are derived from the model's prior alone, not from any real velocity or
fraud-rule signal. The MVP scenarios still produce the right verdicts (Scenario B fails on
compliance, Scenario C on risk band) — so the demo passes — but a production fork would not
treat this as production-ready.

**To close.** Implement either as Spring AI `@Tool` methods on a `RiskTools` bean wired into
RiskAgent's ChatClient, or as a second MCP server (see ADR-005 — currently scope-bounded to one).
Fixture data must be deterministic for the eval suite.

## 2. Grafana cost panel renders no data

**What.** `agentpay_case_cost_usd` is a Micrometer `DistributionSummary` declared in
`SagaMetrics.java`, but `recordCaseCost(BigDecimal)` is either never called or called only with
`null`. After two successful demo runs the metric had no observations:

```
=== agentpay_case_cost_usd_bucket ===
  count=0
=== agentpay_case_cost_usd_sum ===
  (empty)
```

**Where.** The Saga's terminal/suspend transitions in
`services/orchestrator/src/main/java/com/agentpay/orchestrator/saga/PaymentSaga.java` should sum
the per-agent `agent_verdicts.cost_usd` rows for the case and call
`sagaMetrics.recordCaseCost(total)`. The wiring is incomplete.

**Impact.** The "Cost per Case (USD)" panel in `ops/grafana/dashboards/agentplane.json` shows
"No data" even after demo traffic. The other four panels render correctly.

**To close.** Sum `agent_verdicts.cost_usd` for the case and feed it into `recordCaseCost`
inside the same terminal/suspend transaction that emits the Kafka event.

## 3. `agentpay_eval_pass_rate` Grafana panel relies on future textfile collector

**What.** The "Eval Pass Rate" stat panel queries `agentpay_eval_pass_rate`, a metric that does
not yet have a producer. The dashboard JSON
(`ops/grafana/dashboards/agentplane.json`) documents this in the panel description: the eval
runner currently writes `evals/results/<timestamp>.json` and prints stdout summaries; surfacing
the score to Prometheus would require either a textfile collector or a pushgateway. Panel was
intentionally left in place so the dashboard schema stays stable across iterations.

**Impact.** Cosmetic — one of the five NFR-O-004 panels reads "No data" until the producer ships.

**To close.** Either (a) write `evals/results/latest.prom` from the eval runner via a textfile
exporter on Prometheus, or (b) add a pushgateway and a one-shot push at the end of
`./gradlew :evals:test`.

## 4. Langfuse 3.33.0 requires AWS_REGION even with S3 disabled

**What.** Langfuse 3 (≥3.33.0) validates AWS S3 config at startup regardless of whether
`LANGFUSE_S3_EVENT_UPLOAD_ENABLED` / `LANGFUSE_S3_MEDIA_UPLOAD_ENABLED` are `false`. Without
`AWS_REGION` set, container boot fails with "Failed to upload JSON to S3 — Region is missing"
and a `unhandledRejection` that takes the container offline before health-check probes succeed.

**Where.** `docker-compose.yml` langfuse service has `AWS_REGION: us-east-1` as a placeholder
since Iter 7. No actual S3 calls are made — the region satisfies the config validator only.

**Impact.** Currently *resolved* — Langfuse 3.33.0 boots clean with the placeholder. Documented
here so the fix is rediscoverable.

**To close.** Watch Langfuse releases; remove the placeholder once the validator runs only when
the uploaders are enabled.

## 5. Postgres image diverges from REQUIREMENTS.md

**What.** `REQUIREMENTS.md §9` specifies `postgres:16-alpine` with a pgvector init script. The
actual `docker-compose.yml` uses `pgvector/pgvector:pg16`, which bundles pgvector and skips the
`CREATE EXTENSION vector` init step.

**Impact.** Functionally equivalent — the extension is present either way. The pinned image is
arguably a cleaner choice (no init-script ordering concerns), but the divergence from
`REQUIREMENTS.md §9` is undocumented.

**To close.** Either flip `REQUIREMENTS.md §9` to match the actual image, or revert to
`postgres:16-alpine` + the init script. Cosmetic — the system works.

## 6. Iter 6 omission: micrometer-registry-prometheus dep missing

**What.** Through Iter 6, the four Spring Boot services had `spring-boot-starter-actuator` and
listed `prometheus` in `management.endpoints.web.exposure.include`, but lacked the
`io.micrometer:micrometer-registry-prometheus` runtime dep. Result: `/actuator/prometheus` 404'd
silently.

**Where.** Fixed in Iter 7. The dep is now declared in `gradle/libs.versions.toml` and added to
`build.gradle.kts` of gateway, orchestrator, sanctions-mcp, and mock-psp.

**Process learning.** Configuration that "looks right" in YAML is not equivalent to verified
behavior on a running stack. Iter 6 shipped this with `./gradlew build -x test` passing — the
endpoint absence only surfaces when the bytes leave the JVM and someone scrapes them. **From
Iter 7 onward: infrastructure changes require live verification, not just config inspection.**
This is the same class of failure as the Iter 6 evals `NoSuchBeanDefinitionException` shipping
because tests were not actually run.

## 7. Scenario A is non-deterministic when run against the real Anthropic API

**What.** `REQUIREMENTS.md §10.1` expects Scenario A to reach state `COMMITTED`. Live runs
against `claude-sonnet-4-6` occasionally land at `SUSPENDED_FOR_REVIEW` when RiskAgent returns a
score in the REVIEW band (≥50, <80). Two consecutive demo runs during Iter 7 verification
produced SUSPENDED_FOR_REVIEW before a third reached COMMITTED.

**Impact.** `make demo` is not guaranteed to land on COMMITTED on the first try. The integration
test under `services/orchestrator/src/test/java/.../e2e/ScenarioA_HappyPathIT.java` stubs the
model via WireMock, so the test is deterministic; only the *live* demo is flaky.

**To close.** Either (a) tighten the happy-path payload so the model is far less likely to land
in REVIEW, or (b) add a `--seed` / deterministic-stub mode to the buyer client for the demo
flow. Out of Iter 7 scope per the no-prompt-edits prohibition.

## 8. Gateway → Prometheus scrape was 401 before Iter 7

**What.** Before the registry fix, Prometheus reported `gateway: down, server returned HTTP
status 401`. The `/actuator/**` path is on the gateway's permitAll public security chain, but
when the endpoint resolves to nothing the Spring Security filter chain interaction can surface a
401 instead of a 404. Once the registry shipped and `/actuator/prometheus` resolved to a real
exposition response, the 401 disappeared and the target went `up`.

**Impact.** Resolved by Iter 7. Listed here so the symptom is searchable.
