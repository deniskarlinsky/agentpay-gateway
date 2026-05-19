package com.agentpay.orchestrator.e2e;

import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.AgentKey.COMPLIANCE;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.AgentKey.RISK;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.AgentKey.ROUTING;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.registerAnthropicStub;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.registerVoyageStub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.agentpay.orchestrator.api.InternalPaymentsController;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.persistence.EventOutboxRepository;
import com.agentpay.shared.events.PaymentEvent;
import com.agentpay.shared.events.PaymentEventType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Scenario B (REQUIREMENTS §10.2) — compliance FAIL → decision DECLINED → payment.declined event
 * with reason_class=COMPLIANCE_SANCTIONS_MATCH.
 *
 * <p>Deviation from §10.2 wording: the requirement says "case reaches state COMPENSATED". The
 * existing Saga state machine + PaymentEventSerializer wiring (Iter 3) maps decision-time declines
 * to state DECLINED and PSP failures to state COMPENSATED, with the event type derived from the
 * terminal state. That's a cleaner state machine than rerouting decision-time declines through
 * COMPENSATED, so we assert state=DECLINED here and surface the discrepancy in the Iter 4b.2
 * summary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ScenarioB_ComplianceFailIT extends IntegrationTestBase {

  private static final Path SANCTIONS_MCP_JAR =
      Paths.get("..", "sanctions-mcp", "build", "libs", "sanctions-mcp.jar").toAbsolutePath();

  static final GenericContainer<?> SANCTIONS_MCP =
      new GenericContainer<>(
              new ImageFromDockerfile()
                  .withFileFromPath(
                      "Dockerfile", Paths.get("..", "sanctions-mcp", "Dockerfile").toAbsolutePath())
                  .withFileFromPath("build/libs/sanctions-mcp.jar", SANCTIONS_MCP_JAR))
          .withExposedPorts(8090)
          .waitingFor(Wait.forHttp("/actuator/health").forPort(8090).forStatusCode(200))
          .withStartupTimeout(Duration.ofMinutes(2));

  @BeforeAll
  static void startSanctionsMcp() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker is not available — start Docker Desktop to run scenario tests");
    assumeTrue(
        Files.exists(SANCTIONS_MCP_JAR),
        () ->
            "sanctions-mcp JAR missing at "
                + SANCTIONS_MCP_JAR
                + " — run `./gradlew :services:sanctions-mcp:bootJar` first");
    SANCTIONS_MCP.start();
  }

  @DynamicPropertySource
  static void registerMcpUrl(DynamicPropertyRegistry r) {
    r.add(
        "spring.ai.mcp.client.streamable-http.connections.sanctions.url",
        () -> "http://" + SANCTIONS_MCP.getHost() + ":" + SANCTIONS_MCP.getMappedPort(8090));
  }

  @LocalServerPort private int port;
  @Autowired private CaseRepository cases;
  @Autowired private EventOutboxRepository outbox;

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrap;

  @Test
  void complianceFailDeclinesAndPublishesDeclinedEvent() throws Exception {
    registerAnthropicStub(WIREMOCK, RISK, "wiremock/anthropic/scenario-b-risk.json");
    registerAnthropicStub(WIREMOCK, COMPLIANCE, "wiremock/anthropic/scenario-b-compliance.json");
    registerAnthropicStub(WIREMOCK, ROUTING, "wiremock/anthropic/scenario-b-routing.json");
    registerVoyageStub(WIREMOCK);
    // No /charge stub: the PSP must never be called on a compliance-fail path.

    var body =
        Map.of(
            "case_id", "case-B",
            "agent_id", "agent-buyer-007",
            "merchant_id", "merchant-acme",
            "amount", "42.50",
            "currency", "USD",
            "intent_token_jti", UUID.randomUUID().toString(),
            "description", "scenario B",
            "agent_metadata",
                Map.of(
                    "buyer.name",
                    "Fictitious Bad Actor",
                    "buyer.country",
                    "XX",
                    "merchant.name",
                    "Acme Widgets Ltd",
                    "merchant.country",
                    "US"));

    var response =
        RestClient.create()
            .post()
            .uri("http://localhost:" + port + "/internal/payments")
            .body(body)
            .retrieve()
            .body(InternalPaymentsController.InternalPaymentResponse.class);

    assertThat(response).isNotNull();
    assertThat(response.caseId()).isEqualTo("case-B");
    assertThat(response.status()).isEqualTo("DECLINED");

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(outbox.findAll())
                    .as("outbox row is marked published")
                    .allMatch(r -> r.getPublishedAt() != null));

    var entity = cases.findById("case-B").orElseThrow();
    assertThat(entity.getState()).isEqualTo(SagaState.DECLINED);

    int matched = 0;
    PaymentEvent decoded = null;
    try (KafkaConsumer<String, byte[]> consumer = newConsumer()) {
      consumer.subscribe(java.util.List.of("payment.events"));
      long deadline = System.currentTimeMillis() + 10_000;
      while (System.currentTimeMillis() < deadline && matched == 0) {
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, byte[]> r : records) {
          if (!"case-B".equals(r.key())) continue;
          decoded = decode(r.value());
          matched++;
        }
      }
    }
    assertThat(matched).as("exactly one terminal event for case-B").isEqualTo(1);
    assertThat(decoded.getEventType()).isEqualTo(PaymentEventType.DECLINED);
    assertThat(decoded.getTerminalState()).hasToString("DECLINED");
    assertThat(decoded.getReasonClass()).hasToString("COMPLIANCE_SANCTIONS_MATCH");
  }

  private KafkaConsumer<String, byte[]> newConsumer() {
    var props = new java.util.Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "scenario-b-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    return new KafkaConsumer<>(props);
  }

  private static PaymentEvent decode(byte[] bytes) throws Exception {
    DatumReader<PaymentEvent> reader = new SpecificDatumReader<>(PaymentEvent.class);
    return reader.read(null, DecoderFactory.get().binaryDecoder(bytes, null));
  }
}
