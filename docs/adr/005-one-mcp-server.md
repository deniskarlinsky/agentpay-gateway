# ADR-005: One MCP Server (Sanctions Lookup) Only

**Status:** Accepted
**Date:** 2026-05-19

## Context
MCP is the open standard the project wants to demonstrate as a tool-call boundary between an agent and a real downstream service (§2 glossary, FR-M-001). REQUIREMENTS.md §4.2 explicitly bounds the MVP to "more than one MCP server" being out of scope. FR-M-001 mandates one server, implemented with `spring-ai-starter-mcp-server-webmvc` (servlet/sync — the simplest stable variant in Spring AI 1.1.5), exposing `lookup_sanctions(name, country) → SanctionsResult` as the single `@McpTool`-annotated method.

## Decision
We run exactly one MCP server: `services/sanctions-mcp`. It hosts a single tool, `lookup_sanctions`, called by the orchestrator's `ComplianceAgent` during Scenario B. The orchestrator's MCP client is wired via `spring-ai-starter-mcp-client` with `spring.ai.mcp.client.streamable-http.connections.sanctions.url=http://sanctions-mcp:8090`. No other MCP servers, no broker, no registry.

## Consequences
- One tool surface to maintain (`lookup_sanctions`) and one network hop to instrument. The protocol boundary is real — the `ComplianceAgent` chat-model call shows the tool invocation in Langfuse, which is the demonstrable artifact.
- The `@McpTool` / `@McpToolParam` annotations live in `org.springaicommunity.mcp.annotation` (from `mcp-annotations:0.8.0`, pulled transitively by the starter), **not** `org.springframework.ai.*`. The 1.1.5 starter auto-scans `@McpTool` methods on any Spring bean — no manual registration.
- Other specialist agents (`RiskAgent`, `RoutingAgent`) call no MCP tools. The `velocity_check` and `fraud_rules_lookup` tools mentioned in FR-A-R-002 remain unimplemented; they are logged in `docs/known-issues.md` as roadmap work and are noted as such in the agent prompts.
- Compose topology stays small: one extra service, one extra port. Local startup time is bounded.

## Alternatives considered
- **No MCP server — mock `lookup_sanctions` inside `ComplianceAgent` directly.** The reasonable reviewer default for a portfolio scope. Rejected: it defeats the point of demonstrating the protocol boundary, which is half of why MCP is in REQUIREMENTS.md at all.
- **Multiple MCP servers** (one per specialist, or split tools by domain). Rejected: REQUIREMENTS.md §4.2 explicitly lists this as out of scope, and the ceremony cost (a Gradle module, a compose entry, a wire schema, observability config) is high relative to portfolio value at this stage.
- **Embed the tool method in the orchestrator and expose it back to itself over MCP.** Rejected: same protocol-surface as option (b) without the topology honesty of a separate process.

## When to revisit
When a second specialist agent acquires a real external tool dependency, or when REQUIREMENTS.md §4.2 is amended to remove the single-server bound.

Related: [[003-workflow-vs-agent-for-payment-decisioning]].
