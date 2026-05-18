package com.agentpay.orchestrator.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.persistence.SagaTransitionEntity;
import com.agentpay.orchestrator.persistence.SagaTransitionRepository;
import com.agentpay.orchestrator.saga.SagaRecoveryRunner;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * NFR-R-002 / FR-O-002 — Scenario G: orchestrator crash recovery.
 *
 * <p>This is a deliberate SIMULATION of the playbook's literal "SIGKILL the JVM and restart" test
 * (D-3 in the Iter 3 plan). We exercise the same recovery code path — the {@link
 * SagaRecoveryRunner} ApplicationRunner — by pre-seeding a non-terminal case before the Spring
 * context starts. Because the runner fires once on startup, the seeded row is picked up exactly as
 * it would be after a real crash, with full code-path coverage and without the Docker-image build
 * complexity of an actual kill-9.
 */
@SpringBootTest
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
class ScenarioG_CrashRecoveryIT extends IntegrationTestBase {

  static final String CASE_ID = "case-G-recover";

  static {
    // Seed a HELD case BEFORE Spring boots. Postgres + WireMock are already started (the base
    // class init blocks start them). Use a temporary JDBC connection — the orchestrator JPA
    // bean isn't constructed yet at this point.
    seedHeldCase();
    // Stub PSP success for the recovery to converge to COMMITTED.
    WIREMOCK.stubFor(
        post(urlPathEqualTo("/charge"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"case_id":"%s","psp_id":"psp-c","success":true,
                         "auth_code":"AUTH-RECOVER","reason_code":null,"cost_bps":45}
                        """
                            .formatted(CASE_ID))));
  }

  @Autowired private CaseRepository cases;
  @Autowired private SagaTransitionRepository transitions;

  @Test
  void seededHeldCaseConvergesToCommittedOnStartup() {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              CaseEntity entity = cases.findById(CASE_ID).orElseThrow();
              assertThat(entity.getState()).isEqualTo(SagaState.COMMITTED);
            });

    // Audit trail: the recovery should have appended transitions from HELD through to
    // COMMITTED. The original HELD insert predates recovery and remains.
    var rows = transitions.findByCaseIdOrderByCreatedAtAsc(CASE_ID);
    assertThat(rows.stream().map(SagaTransitionEntity::getStateTo))
        .contains(
            SagaState.HELD,
            SagaState.REVIEWING,
            SagaState.APPROVED,
            SagaState.ROUTED,
            SagaState.COMMITTED);
  }

  private static void seedHeldCase() {
    // Run Flyway programmatically. This is idempotent: if a prior test in the same JVM already
    // applied V1, Flyway sees the history row and no-ops. When Spring boots next, its own
    // Flyway auto-config sees V1 applied and likewise no-ops.
    org.flywaydb.core.Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
        .migrate();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    try (var conn =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      try (var ps =
          conn.prepareStatement(
              """
              INSERT INTO cases
                (case_id, agent_id, merchant_id, amount, currency, state, intent_token_jti,
                 created_at, updated_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
              ON CONFLICT (case_id) DO NOTHING
              """)) {
        ps.setString(1, CASE_ID);
        ps.setString(2, "agent-buyer-001");
        ps.setString(3, "merchant-acme");
        ps.setBigDecimal(4, new BigDecimal("42.50"));
        ps.setString(5, "USD");
        ps.setString(6, SagaState.HELD.name());
        ps.setObject(7, UUID.randomUUID());
        ps.setObject(8, now);
        ps.setObject(9, now);
        ps.executeUpdate();
      }
      try (var ps =
          conn.prepareStatement(
              """
              INSERT INTO saga_transitions (case_id, state_from, state_to, reason, created_at)
              VALUES (?, NULL, 'INITIATED', 'intent token accepted', ?),
                     (?, 'INITIATED', 'HELD', 'funds held', ?)
              """)) {
        ps.setString(1, CASE_ID);
        ps.setObject(2, now);
        ps.setString(3, CASE_ID);
        ps.setObject(4, now);
        ps.executeUpdate();
      }
    } catch (Exception e) {
      throw new RuntimeException("Scenario G seed failed", e);
    }
  }
}
