package com.agentpay.orchestrator.e2e;

import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.AgentKey.COMPLIANCE;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.AgentKey.RISK;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.AgentKey.ROUTING;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.registerAnthropicStub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentpay.orchestrator.api.InternalPaymentsController;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.persistence.EventOutboxRepository;
import com.agentpay.shared.events.BudgetExceededEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Scenario F (REQUIREMENTS §10.6, NFR-COST-001) — the per-case budget circuit breaker.
 *
 * <p>Variant locked to "first agent alone exceeds budget" per the Iter 6 plan constraint: the
 * configured per-case budget ({@code $0.0005}) is below the per-call cost of <em>every</em>
 * specialist in the WireMock fixtures, so whichever specialist's {@code CompletableFuture} settles
 * first immediately overflows the budget. The whenComplete callback then trips the {@link
 * java.util.concurrent.atomic.AtomicBoolean} and {@code cancel(true)}'s the other two futures. We
 * assert that the saga reaches {@link SagaState#SUSPENDED_FOR_REVIEW} and exactly one {@code
 * case.budget_exceeded} Kafka event is published with running cost ≥ budget.
 *
 * <p>WireMock fixtures (scenario-f-*.json) bake in token counts that yield deterministic per-call
 * costs: Risk {@code $0.00138}, Compliance {@code $0.00138}, Routing {@code $0.00200} — all above
 * the budget so the first-agent-alone-exceeds assertion holds regardless of which specialist
 * happens to win the race.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(NoOpMcpTestConfig.class)
@TestPropertySource(
    properties = {
      // Scenario F exercises the circuit breaker, not the MCP client. Exclude the eager
      // streamable-http autoconfig so the test does not require a real sanctions-mcp container.
      "spring.autoconfigure.exclude="
          + "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration,"
          + "org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration,"
          + "org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration,"
          + "org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration"
    })
class ScenarioF_BudgetExceededIT extends IntegrationTestBase {

  /**
   * Per-case budget so tight that ANY single specialist's cost alone exceeds it. The smallest
   * per-call cost in the fixtures is {@code $0.00138} (Risk/Compliance Sonnet) and Routing is
   * {@code $0.00200}; {@code $0.0005} is comfortably below all three.
   */
  private static final String BUDGET = "0.0005";

  @LocalServerPort private int port;
  @Autowired private CaseRepository cases;
  @Autowired private EventOutboxRepository outbox;

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrap;

  @DynamicPropertySource
  static void scenarioFProps(DynamicPropertyRegistry r) {
    r.add("agentpay.budget.per_case_usd", () -> BUDGET);
  }

  @Test
  void budgetTrippedByFirstSpecialistAlone() throws Exception {
    String caseId = "case-F-" + UUID.randomUUID().toString().substring(0, 8);
    registerAnthropicStub(WIREMOCK, RISK, "wiremock/anthropic/scenario-f-risk.json");
    registerAnthropicStub(WIREMOCK, COMPLIANCE, "wiremock/anthropic/scenario-f-compliance.json");
    registerAnthropicStub(WIREMOCK, ROUTING, "wiremock/anthropic/scenario-f-routing.json");

    submit(caseId);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var entity = cases.findById(caseId).orElseThrow();
              assertThat(entity.getState()).isEqualTo(SagaState.SUSPENDED_FOR_REVIEW);
            });

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(outbox.findAll())
                    .as("outbox rows for this case are marked published")
                    .filteredOn(r -> caseId.equals(r.getPartitionKey()))
                    .allMatch(r -> r.getPublishedAt() != null));

    int matched = 0;
    BudgetExceededEvent decoded = null;
    try (KafkaConsumer<String, byte[]> consumer = newConsumer()) {
      consumer.subscribe(java.util.List.of("case.budget_exceeded"));
      long deadline = System.currentTimeMillis() + 10_000;
      while (System.currentTimeMillis() < deadline && matched == 0) {
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, byte[]> r : records) {
          if (!caseId.equals(r.key())) {
            continue;
          }
          decoded = decode(r.value());
          matched++;
        }
      }
    }
    assertThat(matched).as("exactly one case.budget_exceeded event for " + caseId).isEqualTo(1);
    assertThat(decoded.getCaseId()).hasToString(caseId);
    BigDecimal running = new BigDecimal(decoded.getRunningCostUsd().toString());
    BigDecimal budget = new BigDecimal(decoded.getBudgetUsd().toString());
    assertThat(running).as("running cost exceeded the configured budget").isGreaterThan(budget);
    assertThat(budget).isEqualByComparingTo(new BigDecimal(BUDGET));
  }

  private void submit(String caseId) {
    Map<String, Object> body =
        Map.of(
            "case_id", caseId,
            "agent_id", "agent-buyer-001",
            "merchant_id", "merchant-acme",
            "amount", "42.50",
            "currency", "USD",
            "intent_token_jti", UUID.randomUUID().toString(),
            "description", "scenario F",
            "agent_metadata",
                Map.of(
                    "buyer.name", "Alice Buyer",
                    "buyer.country", "US",
                    "merchant.name", "Acme Widgets Ltd",
                    "merchant.country", "US"));
    RestClient.create()
        .post()
        .uri("http://localhost:" + port + "/internal/payments")
        .body(body)
        .retrieve()
        .body(InternalPaymentsController.InternalPaymentResponse.class);
  }

  private KafkaConsumer<String, byte[]> newConsumer() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "scenario-f-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    return new KafkaConsumer<>(props);
  }

  private static BudgetExceededEvent decode(byte[] bytes) throws Exception {
    DatumReader<BudgetExceededEvent> reader = new SpecificDatumReader<>(BudgetExceededEvent.class);
    return reader.read(null, DecoderFactory.get().binaryDecoder(bytes, null));
  }
}
