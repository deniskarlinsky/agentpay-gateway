package com.agentpay.orchestrator.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * Test helper: registers WireMock stubs for the three specialist agents' Anthropic calls and the
 * one Voyage embeddings call, dispatching by a unique substring of each agent's system prompt.
 * Fixture JSONs live under {@code src/test/resources/wiremock/anthropic/}.
 */
final class AnthropicWireMockStubs {

  /**
   * Each value is a substring that appears ONLY in one agent's system prompt. The agent-name tokens
   * (e.g. "RiskAgent") are not unique because the prompts cross-reference each other; the
   * verdict-record class names ARE unique because each prompt names only its own output schema.
   */
  enum AgentKey {
    RISK("RiskAssessment"),
    COMPLIANCE("ComplianceVerdict"),
    ROUTING("RouteRecommendation");

    final String systemPromptMarker;

    AgentKey(String marker) {
      this.systemPromptMarker = marker;
    }
  }

  private AnthropicWireMockStubs() {}

  /**
   * Registers a stub returning {@code fixturePath} for any Anthropic /v1/messages request whose
   * top-level {@code system} field contains the agent's marker. WireMock evaluates stubs in
   * registration order, but with non-overlapping markers the match is unambiguous.
   */
  static void registerAnthropicStub(
      WireMockExtension wiremock, AgentKey agent, String fixturePath) {
    String body = loadFixture(fixturePath);
    wiremock.stubFor(
        post(urlPathEqualTo("/v1/messages"))
            .withRequestBody(matchingJsonPath("$.system", containing(agent.systemPromptMarker)))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  /**
   * Registers a single stub for Voyage embeddings returning a constant 512-dim vector for any
   * request body. RoutingAgent's pgvector path is exercised but the corpus → query similarity is
   * trivialized (every input embeds identically), keeping the test deterministic.
   */
  static void registerVoyageStub(WireMockExtension wiremock) {
    StringBuilder embedding = new StringBuilder("[");
    for (int i = 0; i < 512; i++) {
      if (i > 0) {
        embedding.append(',');
      }
      // Distinct value per dimension so the vector has a defined cosine norm.
      // Locale.ROOT is critical: %.4f on a comma-decimal locale would emit invalid JSON.
      embedding.append(String.format(java.util.Locale.ROOT, "%.4f", 0.001 + i * 0.001));
    }
    embedding.append(']');

    String body =
        """
        {
          "object": "list",
          "data": [
            {"object": "embedding", "embedding": %s, "index": 0},
            {"object": "embedding", "embedding": %s, "index": 1},
            {"object": "embedding", "embedding": %s, "index": 2}
          ],
          "model": "voyage-3-lite",
          "usage": {"total_tokens": 30}
        }
        """
            .formatted(embedding, embedding, embedding);
    wiremock.stubFor(
        post(urlPathEqualTo("/v1/embeddings"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private static String loadFixture(String resourcePath) {
    try {
      return StreamUtils.copyToString(
          new ClassPathResource(resourcePath).getInputStream(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to load WireMock fixture " + resourcePath, e);
    }
  }

  /** Tiny wrapper so we can pass an "expression contains substring" matcher to WireMock. */
  private static com.github.tomakehurst.wiremock.matching.StringValuePattern containing(
      String substring) {
    return com.github.tomakehurst.wiremock.client.WireMock.containing(substring);
  }
}
