# ADR-001: Pin to Java 21 LTS + Spring Boot 3.5.x + Spring AI 1.1.5

**Status:** Accepted
**Date:** 2026-05-19

## Context
At project inception (May 2026) three newer stacks were within arm's reach: Spring AI 2.0 GA was imminent, Spring Boot 4.0 had just released, and Java 25 LTS had been out roughly eight months. None had six months of production maturity behind it. Spring AI 2.0 in particular had the features this project would have liked to lean on — `OrchestratorWorkersWorkflow`, the A2A starter, an updated MCP surface — but its release timing collided directly with Spring Boot 4.0's, and the documented compatibility matrix was thin. This is a pet/portfolio project; debugging cross-stack version drift is not the artifact.

## Decision
We pin Java 21 LTS, Spring Boot 3.5.x, and Spring AI 1.1.5 in `gradle/libs.versions.toml`. No `-M`, `-RC`, or `-SNAPSHOT` anywhere. No preview features. No `@Experimental` APIs even from stable libraries. CLAUDE.md §4 enforces this; CI greps the dependency tree.

## Consequences
- Three doors close, each compensated elsewhere and documented:
  - No Spring AI A2A starter (needs Boot 4 + AI 2.0). The A2A surface collapses to one discovery endpoint. See [[004-a2a-discovery-only]].
  - No `StructuredTaskScope` (preview through Java 25). Fan-out uses `Executors.newVirtualThreadPerTaskExecutor()` + `CompletableFuture.allOf`. See [[006-virtual-threads-no-preview]].
  - No `OrchestratorWorkersWorkflow`. The Supervisor is hand-written — ~60 lines, all visible in one class. See [[003-workflow-vs-agent-for-payment-decisioning]].
- The build is reproducible. A reviewer cloning the repo today gets the same artifact a reviewer cloning it next year would get.
- Spring AI 1.1.5 has the MCP server/client surface this project needs, and its observability emission is already Micrometer-native.
- The cost is real: every "the new version has this for free" moment is a small additional file in this repo. That's acceptable; the additional files are themselves architecturally instructive.

## Alternatives considered
- **Spring AI 2.0 + Spring Boot 4.0 + Java 25.** The reviewer-default option. Rejected: version-mismatch risk during pet-project work is unacceptable; debugging a Spring AI 2.0-M / Boot 4.0 RC interaction is not the artifact this repo is trying to be.
- **Spring AI 1.0.x on Spring Boot 3.4.** Safer on paper, but the MCP server surface is materially weaker and the observability story regresses to manual instrumentation.

## When to revisit
When the newer stack (Spring AI 2.0 + Spring Boot 4.0 + Java 25) has six or more months of production maturity behind it across all three layers — measured by GA date plus a clean compatibility matrix, not by individual library versions.

Related: [[003-workflow-vs-agent-for-payment-decisioning]], [[004-a2a-discovery-only]], [[006-virtual-threads-no-preview]].
