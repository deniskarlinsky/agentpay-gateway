package com.agentpay.orchestrator.e2e;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only stand-in for the Spring AI MCP client autoconfiguration. Iter 4b.1 added a hard
 * dependency on a running sanctions-mcp at context startup (the streamable-http transport
 * autoconfig opens its connection eagerly). Scenarios A and G do not exercise ComplianceAgent — the
 * Iter 3 stub Supervisor returns APPROVED unconditionally — so the MCP client has no business
 * loading in those tests. We exclude the eager autoconfigs via {@code spring.autoconfigure.exclude}
 * on each scenario test class and provide an empty {@link ToolCallbackProvider} here so
 * ComplianceAgent's bean creation still resolves.
 *
 * <p>ComplianceAgentSmokeTest intentionally does NOT import this — that test exercises real
 * autoconfig against a real sanctions-mcp container.
 */
@TestConfiguration
public class NoOpMcpTestConfig {

  @Bean
  public ToolCallbackProvider noOpMcpToolCallbackProvider() {
    return () -> new ToolCallback[0];
  }
}
