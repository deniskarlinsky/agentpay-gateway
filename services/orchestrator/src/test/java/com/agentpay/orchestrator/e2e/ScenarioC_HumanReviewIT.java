package com.agentpay.orchestrator.e2e;

import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.AgentKey.COMPLIANCE;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.AgentKey.RISK;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.AgentKey.ROUTING;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.registerAnthropicStub;
import static com.agentpay.orchestrator.e2e.AnthropicWireMockStubs.registerVoyageStub;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.agentpay.orchestrator.api.InternalPaymentsController;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.shared.events.HumanApprovalDecision;
import com.agentpay.shared.events.HumanApprovalRequest;
import com.agentpay.shared.events.HumanApprovalResponse;
import com.agentpay.shared.events.PaymentEvent;
import com.agentpay.shared.events.PaymentEventType;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Scenario C (REQUIREMENTS §10.3, FR-O-005) — risk score 65 triggers REVIEW, the case enters
 * SUSPENDED_FOR_REVIEW with a {@code human.approval.requests} outbox row, then either resumes to
 * COMMITTED on GRANTED or transitions to DECLINED on DENIED. ScenarioG-style pre-seeding covers the
 * crash-recovery analogue.
 *
 * <p>The MCP autoconfig is excluded (same exclusions as ScenarioG): {@link NoOpMcpTestConfig} gives
 * ComplianceAgent an empty {@code ToolCallbackProvider}, and the WireMock'd Anthropic response for
 * compliance returns PASS text without requesting a tool — so no sanctions-mcp container is needed.
 */
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
class ScenarioC_HumanReviewIT extends IntegrationTestBase {

  static final String SEEDED_CASE_ID = "case-C4-recover";

  static {
    // Seed a SUSPENDED_FOR_REVIEW case BEFORE Spring boots, exactly like ScenarioG. Postgres is
    // already started by IntegrationTestBase's static initialiser. Flyway is run programmatically
    // here so the V3 column exists at INSERT time; Spring's own Flyway autoconfig will see the
    // history row and no-op when the orchestrator context starts.
    seedSuspendedCase();
  }

  @BeforeAll
  static void dockerAvailable() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker is not available — start Docker Desktop to run scenario tests");
  }

  @LocalServerPort private int port;
  @Autowired private CaseRepository cases;

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrap;

  @Test
  void test_suspends_and_publishes_request() throws Exception {
    String caseId = "case-C1";
    submitScenarioCPayment(caseId);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              CaseEntity entity = cases.findById(caseId).orElseThrow();
              assertThat(entity.getState()).isEqualTo(SagaState.SUSPENDED_FOR_REVIEW);
            });

    CaseEntity persisted = cases.findById(caseId).orElseThrow();
    assertThat(persisted.getDecisionJsonb()).as("decision_jsonb populated on suspend").isNotNull();
    assertThat(persisted.getDecisionJsonb().get("riskScore").asInt()).isEqualTo(65);
    assertThat(persisted.getDecisionJsonb().get("outcome").asText()).isEqualTo("REVIEW");
    assertThat(persisted.getAgentMetadataJsonb())
        .as("agent_metadata_jsonb populated at INITIATED")
        .isNotNull()
        .containsEntry("buyer.name", "Bob Reviewer")
        .containsEntry("buyer.country", "DE");

    // Saga is truly waiting — no further transitions for 2s.
    SagaState before = persisted.getState();
    Thread.sleep(2_000);
    CaseEntity after = cases.findById(caseId).orElseThrow();
    assertThat(after.getState()).isEqualTo(before);

    HumanApprovalRequest request = awaitOneApprovalRequest(caseId, Duration.ofSeconds(5));
    assertThat(request.getCaseId()).hasToString(caseId);
    assertThat(request.getRiskScore()).isEqualTo(65);
    assertThat(request.getComplianceOutcome()).hasToString("PASS");
  }

  @Test
  void test_granted_resumes_to_committed() throws Exception {
    String caseId = "case-C2";
    stubPspSuccess(caseId);
    submitScenarioCPayment(caseId);
    awaitSuspended(caseId);

    publishApprovalResponse(caseId, HumanApprovalDecision.GRANTED, "on-call-alice", null);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(cases.findById(caseId).orElseThrow().getState())
                    .isEqualTo(SagaState.COMMITTED));

    PaymentEvent terminal = awaitOnePaymentEvent(caseId, Duration.ofSeconds(10));
    assertThat(terminal.getEventType()).isEqualTo(PaymentEventType.COMPLETED);
    assertThat(terminal.getTerminalState()).hasToString("COMMITTED");
  }

  @Test
  void test_denied_transitions_to_declined() throws Exception {
    String caseId = "case-C3";
    submitScenarioCPayment(caseId);
    awaitSuspended(caseId);

    publishApprovalResponse(
        caseId, HumanApprovalDecision.DENIED, "on-call-alice", "manual_review_rejected");

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(cases.findById(caseId).orElseThrow().getState())
                    .isEqualTo(SagaState.DECLINED));

    PaymentEvent terminal = awaitOnePaymentEvent(caseId, Duration.ofSeconds(10));
    assertThat(terminal.getEventType()).isEqualTo(PaymentEventType.DECLINED);
    assertThat(terminal.getReasonClass()).hasToString("HUMAN_REVIEW_DENIED");
  }

  /**
   * Crash-recovery analogue (Scenario G pattern). The static seed inserts a {@code
   * SUSPENDED_FOR_REVIEW} case with a hydrated {@code decision_jsonb} BEFORE Spring boots, so the
   * application context comes up with the case already present. SagaRecoveryRunner must observe the
   * SUSPENDED state and decline to advance it (driveForward early-returns on SUSPENDED_FOR_REVIEW).
   * Then we publish GRANTED and assert the case resumes to COMMITTED — which proves the Decision
   * survived restart via the JSONB column, not via the lost in-memory map.
   */
  @Test
  void test_crash_recovery_preserves_review_state() throws Exception {
    String caseId = SEEDED_CASE_ID;
    stubPspSuccess(caseId);

    // SagaRecoveryRunner ran at startup. The seeded SUSPENDED_FOR_REVIEW case must not have been
    // advanced — driveForward's SUSPENDED_FOR_REVIEW branch returns without stepping.
    CaseEntity seeded = cases.findById(caseId).orElseThrow();
    assertThat(seeded.getState()).isEqualTo(SagaState.SUSPENDED_FOR_REVIEW);
    assertThat(seeded.getDecisionJsonb())
        .as("seeded decision_jsonb preserved across context boot")
        .isNotNull();
    assertThat(seeded.getDecisionJsonb().get("outcome").asText()).isEqualTo("REVIEW");

    publishApprovalResponse(caseId, HumanApprovalDecision.GRANTED, "on-call-bob", null);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(cases.findById(caseId).orElseThrow().getState())
                    .isEqualTo(SagaState.COMMITTED));
  }

  // ---- helpers -----------------------------------------------------------------------------

  private void submitScenarioCPayment(String caseId) {
    registerAnthropicStub(WIREMOCK, RISK, "wiremock/anthropic/scenario-c-risk.json");
    registerAnthropicStub(WIREMOCK, COMPLIANCE, "wiremock/anthropic/scenario-c-compliance.json");
    registerAnthropicStub(WIREMOCK, ROUTING, "wiremock/anthropic/scenario-c-routing.json");
    registerVoyageStub(WIREMOCK);

    var body =
        Map.of(
            "case_id", caseId,
            "agent_id", "agent-buyer-002",
            "merchant_id", "merchant-acme",
            "amount", "42.50",
            "currency", "USD",
            "intent_token_jti", UUID.randomUUID().toString(),
            "description", "scenario C — review",
            "agent_metadata",
                Map.of(
                    "buyer.name", "Bob Reviewer",
                    "buyer.country", "DE",
                    "merchant.name", "Acme Widgets Ltd",
                    "merchant.country", "US"));

    RestClient.create()
        .post()
        .uri("http://localhost:" + port + "/internal/payments")
        .body(body)
        .retrieve()
        .body(InternalPaymentsController.InternalPaymentResponse.class);
  }

  private void stubPspSuccess(String caseId) {
    WIREMOCK.stubFor(
        post(urlPathEqualTo("/charge"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"case_id":"%s","psp_id":"psp-a","success":true,
                         "auth_code":"AUTH-C","reason_code":null,"cost_bps":30}
                        """
                            .formatted(caseId))));
  }

  private void awaitSuspended(String caseId) {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(cases.findById(caseId).orElseThrow().getState())
                    .isEqualTo(SagaState.SUSPENDED_FOR_REVIEW));
  }

  private void publishApprovalResponse(
      String caseId, HumanApprovalDecision decision, String decidedBy, String reason)
      throws Exception {
    HumanApprovalResponse response =
        HumanApprovalResponse.newBuilder()
            .setCaseId(caseId)
            .setDecision(decision)
            .setDecidedBy(decidedBy)
            .setDecidedAt(Instant.now())
            .setReason(reason)
            .build();
    byte[] payload = encodeResponse(response);
    try (KafkaProducer<String, byte[]> producer = newProducer()) {
      producer.send(new ProducerRecord<>("human.approval.responses", caseId, payload)).get();
    }
  }

  private static byte[] encodeResponse(HumanApprovalResponse response) throws Exception {
    DatumWriter<HumanApprovalResponse> writer =
        new SpecificDatumWriter<>(HumanApprovalResponse.class);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    writer.write(response, encoder);
    encoder.flush();
    return out.toByteArray();
  }

  private KafkaProducer<String, byte[]> newProducer() {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    return new KafkaProducer<>(props);
  }

  private KafkaConsumer<String, byte[]> newConsumer(String topic) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + topic + "-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(props);
    c.subscribe(java.util.List.of(topic));
    return c;
  }

  private HumanApprovalRequest awaitOneApprovalRequest(String caseId, Duration window)
      throws Exception {
    DatumReader<HumanApprovalRequest> reader =
        new SpecificDatumReader<>(HumanApprovalRequest.class);
    try (KafkaConsumer<String, byte[]> consumer = newConsumer("human.approval.requests")) {
      long deadline = System.currentTimeMillis() + window.toMillis();
      while (System.currentTimeMillis() < deadline) {
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, byte[]> r : records) {
          if (!caseId.equals(r.key())) continue;
          return reader.read(null, DecoderFactory.get().binaryDecoder(r.value(), null));
        }
      }
    }
    throw new AssertionError("no HumanApprovalRequest for " + caseId + " within " + window);
  }

  private PaymentEvent awaitOnePaymentEvent(String caseId, Duration window) throws Exception {
    DatumReader<PaymentEvent> reader = new SpecificDatumReader<>(PaymentEvent.class);
    try (KafkaConsumer<String, byte[]> consumer = newConsumer("payment.events")) {
      long deadline = System.currentTimeMillis() + window.toMillis();
      while (System.currentTimeMillis() < deadline) {
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, byte[]> r : records) {
          if (!caseId.equals(r.key())) continue;
          return reader.read(null, DecoderFactory.get().binaryDecoder(r.value(), null));
        }
      }
    }
    throw new AssertionError("no PaymentEvent for " + caseId + " within " + window);
  }

  private static void seedSuspendedCase() {
    org.flywaydb.core.Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
        .migrate();
    java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
    // Decision JSON mirrors the shape PaymentSaga.applyDecisionAtomic writes when Supervisor
    // returns a REVIEW outcome that preserves the computed route (FR-O-005 path). With Jdk8Module
    // active on the application's ObjectMapper, Optional<RouteRecommendation> serializes as the
    // unwrapped object on the "route" key; on hydration it round-trips back to Optional.of(...).
    String decisionJson =
        """
        {
          "outcome":"REVIEW",
          "riskScore":65,
          "compliance":{"outcome":"PASS","citations":[],"rationale":"seed: compliance PASS"},
          "route":{"pspId":"psp-a","routeId":"route-eu-1","expectedSuccessRate":0.952,
                   "expectedCostBps":30,"rationale":"seed: psp-a chosen for recovery"},
          "rationale":["seeded for crash-recovery test"]
        }
        """;
    String metadataJson =
        """
        {"buyer.name":"Carol Reviewer","buyer.country":"NL",
         "merchant.name":"Acme Widgets Ltd","merchant.country":"US"}
        """;
    try (var conn =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      try (var ps =
          conn.prepareStatement(
              """
              INSERT INTO cases
                (case_id, agent_id, merchant_id, amount, currency, state, intent_token_jti,
                 created_at, updated_at, decision_jsonb, agent_metadata_jsonb)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
              ON CONFLICT (case_id) DO NOTHING
              """)) {
        ps.setString(1, SEEDED_CASE_ID);
        ps.setString(2, "agent-buyer-003");
        ps.setString(3, "merchant-acme");
        ps.setBigDecimal(4, new java.math.BigDecimal("42.50"));
        ps.setString(5, "USD");
        ps.setString(6, SagaState.SUSPENDED_FOR_REVIEW.name());
        ps.setObject(7, UUID.randomUUID());
        ps.setObject(8, now);
        ps.setObject(9, now);
        ps.setString(10, decisionJson);
        ps.setString(11, metadataJson);
        ps.executeUpdate();
      }
      try (var ps =
          conn.prepareStatement(
              """
              INSERT INTO saga_transitions (case_id, state_from, state_to, reason, created_at)
              VALUES (?, NULL, 'INITIATED', 'seed', ?),
                     (?, 'INITIATED', 'HELD', 'seed', ?),
                     (?, 'HELD', 'REVIEWING', 'seed', ?),
                     (?, 'REVIEWING', 'SUSPENDED_FOR_REVIEW', NULL, ?)
              """)) {
        ps.setString(1, SEEDED_CASE_ID);
        ps.setObject(2, now);
        ps.setString(3, SEEDED_CASE_ID);
        ps.setObject(4, now);
        ps.setString(5, SEEDED_CASE_ID);
        ps.setObject(6, now);
        ps.setString(7, SEEDED_CASE_ID);
        ps.setObject(8, now);
        ps.executeUpdate();
      }
    } catch (Exception e) {
      throw new RuntimeException("Scenario C seed failed", e);
    }
  }
}
