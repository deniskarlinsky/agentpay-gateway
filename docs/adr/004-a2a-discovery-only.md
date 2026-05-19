# ADR-004: A2A Discovery Endpoint Only — No JSON-RPC Surface

**Status:** Accepted
**Date:** 2026-05-19

## Context
The gateway is agent-native and must advertise itself to clients that perform A2A discovery (§4.1, glossary "A2A discovery"). The Spring AI A2A starter (`spring-ai-community/spring-ai-a2a`) that would implement the full protocol — JSON-RPC `sendMessage`, capability negotiation, multi-hop, federation — requires Spring Boot 4.0+ and Spring AI 2.0.0-M2+. Both are outside [ADR-001](001-stable-stack-baseline.md)'s pinned stack (Spring Boot 3.5.x, Spring AI 1.1.5, no `-M` versions). REQUIREMENTS.md §4.2 explicitly lists full A2A as out of scope and ties the exclusion to this stack constraint.

## Decision
We expose A2A as a single static discovery endpoint: `GET /.well-known/agent.json` returning a constant `AgentCard` record (name, description, URL, version, protocol version, capabilities, supported input/output modes, skill list). The full request-handling surface is deferred. Implementation lives in `services/gateway/src/main/java/com/agentpay/gateway/web/AgentCardController.java` — ~30 lines, one `@GetMapping`, one private static final `AgentCard`.

## Consequences
- Any A2A-aware client performing standard discovery learns the gateway's name, URL, version, the two advertised skills (`request_intent_token`, `submit_payment`), and supported MIME types. That is enough to demonstrate agent-native intent end-to-end.
- Zero runtime cost — the response is a constant. No serialization framework hooks, no capability handshake state.
- No support for `message/send`, streaming, or peer-to-peer agent calls. Buyer agents continue to call the gateway over plain REST + intent-token JWTs. This is fine for the MVP scenarios; non-discovery A2A behaviour is logged in `docs/roadmap.md`.
- Skills advertised in the card and the REST surface they describe must stay in sync by hand. Acceptable at two skills; would need a generator if the surface grew.

## Alternatives considered
- **Pull the Spring AI 2.0-M / Spring Boot 4 A2A starter onto a sidecar module** — rejected: violates [ADR-001](001-stable-stack-baseline.md). The stack policy is the more valuable invariant than protocol completeness for a portfolio project.
- **No discovery endpoint, document the REST API in README only** — the reasonable reviewer default. Rejected: the entire point of an agent-native gateway is being discoverable through the protocol agents actually use; a constant JSON file is the cheapest honest implementation.
- **Hand-roll the JSON-RPC `sendMessage` handler** — rejected: re-implementing a wire protocol for one MVP demo is exactly the speculative work CLAUDE.md §3 forbids.

## When to revisit
When Spring Boot 4 and Spring AI 2 have both reached 6+ months of production maturity ([ADR-001](001-stable-stack-baseline.md)'s revisit trigger), or when a second concrete agent-to-agent integration appears in REQUIREMENTS.md.

Related: [[001-stable-stack-baseline]].
