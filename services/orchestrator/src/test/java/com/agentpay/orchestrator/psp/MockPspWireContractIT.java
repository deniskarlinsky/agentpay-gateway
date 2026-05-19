package com.agentpay.orchestrator.psp;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentpay.mockpsp.MockPspApplication;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

/**
 * Wire-contract regression for the live-demo "every Scenario A run terminates as COMPENSATED" bug.
 * The orchestrator's {@link MockPspClient.ChargeRequest} originally serialised with default
 * camelCase keys ({@code caseId}, {@code pspId}), but mock-psp's {@code ChargeRequest} record binds
 * via {@code @JsonProperty("case_id")} / {@code @JsonProperty("psp_id")}. The mismatch made every
 * live charge land with {@code pspId=null} → unknown profile → {@code success=false} → orchestrator
 * COMPENSATED the case. WireMock-based scenario tests masked the bug because WireMock doesn't
 * validate request bodies — it just replays the stubbed fixture.
 *
 * <p>This test brings up the real {@link MockPspApplication} on a random port in the same JVM and
 * drives {@link MockPspClient#charge} against it. A {@code success=true} result here proves the
 * wire format matches in both directions; pre-fix, this returned {@code success=false} with {@code
 * reason_code="AC01"} (unknown-profile path).
 */
class MockPspWireContractIT {

  private static ConfigurableApplicationContext mockPspContext;
  private static MockPspClient pspClient;

  @BeforeAll
  static void startMockPsp() {
    // Mock-psp's @SpringBootApplication scans only its own package, but autoconfig fires off the
    // orchestrator's classpath (Spring AI starters, JPA, Kafka, Redis…). Exclude every autoconfig
    // mock-psp does not actually use so the side-context boots without a DB, vector store, etc.
    //
    // Also pin the PSP profile map inline: the orchestrator's own application.yml shadows
    // mock-psp's bundled one when both jars are on the classpath, so we re-declare the profiles
    // here. Match the values in services/mock-psp/src/main/resources/application.yml.
    mockPspContext =
        new SpringApplicationBuilder(MockPspApplication.class)
            .web(WebApplicationType.SERVLET)
            // SpringApplicationBuilder.properties(...) sets *default* properties which are
            // outranked by mock-psp's bundled application.yml (server.port=8091). Command-line
            // args have the highest precedence — use --server.port=0 to actually pick a free port.
            .properties(
                "spring.main.banner-mode=off",
                "mock-psp.profiles.psp-a.success-rate=0.95",
                "mock-psp.profiles.psp-a.cost-bps=30",
                "mock-psp.profiles.psp-b.success-rate=0.88",
                "mock-psp.profiles.psp-b.cost-bps=20",
                "mock-psp.profiles.psp-c.success-rate=0.98",
                "mock-psp.profiles.psp-c.cost-bps=45",
                "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                    + "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,"
                    + "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration,"
                    + "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration,"
                    + "org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration,"
                    + "org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration,"
                    + "org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration")
            .run("--server.port=0");
    int port = ((WebServerApplicationContext) mockPspContext).getWebServer().getPort();
    pspClient = new MockPspClient(RestClient.builder(), "http://localhost:" + port);
  }

  @AfterAll
  static void stopMockPsp() {
    if (mockPspContext != null) {
      mockPspContext.close();
    }
  }

  @Test
  void chargeWithKnownPspIdReturnsSuccess() {
    // psp-c has success-rate=0.98 in mock-psp's application.yml — the deterministic bucket for
    // this caseId/pspId pair is well under the success threshold.
    MockPspClient.ChargeResponse response =
        pspClient.charge(
            new MockPspClient.ChargeRequest(
                "case-wire-1", new BigDecimal("42.50"), "USD", "psp-c", "route-us-1"));

    // The bug surfaces here. Before the fix: orchestrator sends camelCase JSON, mock-psp parses
    // {pspId} as null, profile lookup fails, returns success=false with reason_code=AC01.
    // After the fix (snake_case @JsonProperty annotations on both ChargeRequest/Response):
    // mock-psp matches psp-c profile, deterministic bucket succeeds, success=true.
    assertThat(response.success())
        .as("orchestrator → mock-psp snake_case wire contract must produce success=true")
        .isTrue();
    assertThat(response.pspId()).isEqualTo("psp-c");
    assertThat(response.caseId()).isEqualTo("case-wire-1");
    assertThat(response.authCode()).startsWith("AUTH-");
    assertThat(response.reasonCode()).isNull();
    assertThat(response.costBps()).isGreaterThan(0);
  }

  @Test
  void chargeWithUnknownPspIdReturnsAc01() {
    // Sanity check: an actually-unknown pspId surfaces as AC01. Distinguishes the success-path
    // regression (snake_case-mismatch null pspId) from the legitimate unknown-pspId AC01.
    MockPspClient.ChargeResponse response =
        pspClient.charge(
            new MockPspClient.ChargeRequest(
                "case-wire-2", new BigDecimal("42.50"), "USD", "psp-zzz-unknown", "route-x"));

    assertThat(response.success()).isFalse();
    assertThat(response.reasonCode()).isEqualTo("AC01");
  }
}
