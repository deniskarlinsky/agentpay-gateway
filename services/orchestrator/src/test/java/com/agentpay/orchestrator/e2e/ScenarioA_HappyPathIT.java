package com.agentpay.orchestrator.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentpay.orchestrator.api.InternalPaymentsController;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.persistence.EventOutboxRepository;
import com.agentpay.shared.events.PaymentEvent;
import com.agentpay.shared.events.PaymentEventType;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(NoOpMcpTestConfig.class)
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration,"
          + "org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration,"
          + "org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration,"
          + "org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration"
    })
class ScenarioA_HappyPathIT extends IntegrationTestBase {

  @LocalServerPort private int port;
  @Autowired private CaseRepository cases;
  @Autowired private EventOutboxRepository outbox;

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrap;

  @Test
  void happyPathReachesCommittedAndPublishesExactlyOneEvent() throws Exception {
    // Stub PSP to always succeed (deterministic for tests; mock-psp's real determinism is unit-
    // tested in services/mock-psp).
    WIREMOCK.stubFor(
        post(urlPathEqualTo("/charge"))
            .withHeader("Content-Type", equalTo("application/json"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"case_id":"case-A","psp_id":"psp-c","success":true,
                         "auth_code":"AUTH-TEST-1","reason_code":null,"cost_bps":45}
                        """)));

    var body =
        Map.of(
            "case_id", "case-A",
            "agent_id", "agent-buyer-001",
            "merchant_id", "merchant-acme",
            "amount", "42.50",
            "currency", "USD",
            "intent_token_jti", UUID.randomUUID().toString(),
            "description", "scenario A");

    var response =
        RestClient.create()
            .post()
            .uri("http://localhost:" + port + "/internal/payments")
            .body(body)
            .retrieve()
            .body(InternalPaymentsController.InternalPaymentResponse.class);

    assertThat(response).isNotNull();
    assertThat(response.caseId()).isEqualTo("case-A");
    assertThat(response.status()).isEqualTo("COMMITTED");
    assertThat(response.duplicate()).isFalse();

    // OutboxPublisher must drain the row to Kafka within the poll interval window.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(outbox.findAll())
                    .as("outbox row is marked published")
                    .allMatch(r -> r.getPublishedAt() != null));

    var entity = cases.findById("case-A").orElseThrow();
    assertThat(entity.getState()).isEqualTo(SagaState.COMMITTED);

    // Consume payment.events and assert exactly one COMPLETED event for this case (FR-O-009).
    int matched = 0;
    PaymentEvent decoded = null;
    try (KafkaConsumer<String, byte[]> consumer = newConsumer()) {
      consumer.subscribe(java.util.List.of("payment.events"));
      long deadline = System.currentTimeMillis() + 10_000;
      while (System.currentTimeMillis() < deadline && matched == 0) {
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, byte[]> r : records) {
          if (!"case-A".equals(r.key())) continue;
          decoded = decode(r.value());
          matched++;
        }
      }
    }
    assertThat(matched).as("exactly one terminal event for case-A").isEqualTo(1);
    assertThat(decoded.getEventType()).isEqualTo(PaymentEventType.COMPLETED);
    assertThat(decoded.getTerminalState()).hasToString("COMMITTED");
  }

  private KafkaConsumer<String, byte[]> newConsumer() {
    var props = new java.util.Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "scenario-a-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    // read_committed so the transactional producer's commits are honored (NFR-R-003).
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
