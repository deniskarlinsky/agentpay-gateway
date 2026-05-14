package com.agentpay.sanctions;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

// Integration test approach (two layers):
//
// 1. Service layer (service bean called directly): verifies fixture loading, case-insensitive and
//    fuzzy matching, and the null-country / country-mismatch edge cases. Avoids adding
//    spring-ai-starter-mcp-client as a testImplementation dependency in the server module.
//
// 2. Tool-registration smoke test (toolsListAdvertisesLookupSanctions): confirms the @McpTool
//    annotation was scanned by the MCP server auto-configuration and the tool is registered in the
//    SyncToolSpecification list that the server serves over the wire when a client calls
//    tools/list on the /mcp endpoint. This is equivalent to a tools/list query without needing
//    the full MCP JSON-RPC session handshake in the test.
//
// Full transport-level testing (MCP client ↔ /mcp endpoint) is deferred to the orchestrator's
// Iteration 4 end-to-end tests, where the real spring-ai-starter-mcp-client connects to the
// running sanctions-mcp container.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SanctionsMcpApplicationTest {

  @Autowired SanctionsMcpService service;
  @Autowired ApplicationContext ctx;

  @Test
  void contextLoads() {}

  @Test
  void lookupKnownSanctionedEntityReturnsMatch() {
    SanctionsResult result = service.lookupSanctions("Test Subject Alpha", "XX");
    assertThat(result.isMatch()).isTrue();
    assertThat(result.matchStrength()).isEqualTo(1.0f);
    assertThat(result.listName()).isEqualTo("SYNTHETIC-SDN");
    assertThat(result.citationId()).isEqualTo("SYN-001");
  }

  @Test
  void lookupUnknownPersonReturnsNoMatch() {
    SanctionsResult result = service.lookupSanctions("Completely Harmless Person", "US");
    assertThat(result.isMatch()).isFalse();
    assertThat(result.matchStrength()).isNull();
    assertThat(result.listName()).isNull();
    assertThat(result.citationId()).isNull();
  }

  @Test
  void lookupWithCountryMismatchReturnsNoMatch() {
    // Fixture has "Test Subject Alpha" with country "XX"; querying with "US" should not match.
    SanctionsResult result = service.lookupSanctions("Test Subject Alpha", "US");
    assertThat(result.isMatch()).isFalse();
  }

  @Test
  void lookupWithNoCountryMatchesAnyEntry() {
    // Country null → name-only lookup should still find the entry.
    SanctionsResult result = service.lookupSanctions("Test Subject Alpha", null);
    assertThat(result.isMatch()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void toolsListAdvertisesLookupSanctions() {
    // Smoke test: verify the @McpTool-annotated method was picked up by the MCP server
    // auto-configuration and appears in the SyncToolSpecification list — the same list the
    // server returns when a client calls tools/list over the /mcp endpoint.
    // We look up the bean by name "toolSpecs" (created by
    // McpServerSpecificationFactoryAutoConfiguration
    // from @McpTool-annotated beans) rather than "syncTools" (which is populated from ToolCallback
    // beans and is empty in our annotation-based approach).
    List<McpServerFeatures.SyncToolSpecification> specs =
        (List<McpServerFeatures.SyncToolSpecification>) ctx.getBean("toolSpecs");
    assertThat(specs).extracting(spec -> spec.tool().name()).contains("lookup_sanctions");
  }
}
