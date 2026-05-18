package com.agentpay.orchestrator.agents.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.agentpay.orchestrator.agents.ComplianceAgent;
import com.agentpay.orchestrator.agents.support.AgentVerdictRepository;
import com.agentpay.orchestrator.domain.ComplianceVerdict;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.orchestrator.persistence.CaseRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Smoke test for Iter 4b.1 — verifies ChatClient + MCP client + persistence wire up correctly
 * against the real Anthropic API and a real sanctions-mcp container.
 *
 * <p>Disabled unless {@code ANTHROPIC_API_KEY} is set. The sanctions-mcp JAR must already be built
 * (see {@code services/sanctions-mcp/build/libs/sanctions-mcp.jar}); if missing, the test fails an
 * {@code assumeTrue} precondition with a clear message so the user knows to run a build first.
 *
 * <p>Iter 4b.2 will subsume this once Scenarios A/B/C exercise the supervisor end-to-end. Until
 * then, this is the integration anchor: if it passes, 4b.2 only needs to add the supervisor layer.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = "sk-ant-.*")
class ComplianceAgentSmokeTest {

  private static final Path SANCTIONS_MCP_JAR =
      Paths.get("..", "sanctions-mcp", "build", "libs", "sanctions-mcp.jar").toAbsolutePath();

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("agentpay")
          .withUsername("agentpay")
          .withPassword("agentpay")
          .withInitScript("init/01-init-test.sql");

  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.1");

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
  static void startContainers() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker is not available — start Docker Desktop to run the smoke test");
    assumeTrue(
        Files.exists(SANCTIONS_MCP_JAR),
        () ->
            "sanctions-mcp JAR missing at "
                + SANCTIONS_MCP_JAR
                + " — run `./gradlew :services:sanctions-mcp:bootJar` first");
    POSTGRES.start();
    KAFKA.start();
    SANCTIONS_MCP.start();
  }

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add(
        "spring.ai.mcp.client.streamable-http.connections.sanctions.url",
        () -> "http://" + SANCTIONS_MCP.getHost() + ":" + SANCTIONS_MCP.getMappedPort(8090));
  }

  @Autowired private ComplianceAgent complianceAgent;
  @Autowired private AgentVerdictRepository verdictRepo;
  @Autowired private CaseRepository caseRepo;

  @Test
  void clearIdentityYieldsPassAndPersistsVerdict() {
    // FK constraint on agent_verdicts.case_id → cases.case_id: seed a case row first.
    String caseId = "smoke-" + UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    caseRepo.save(
        new CaseEntity(
            caseId,
            "agent-buyer-smoke",
            "merchant-acme",
            new BigDecimal("18.00"),
            "USD",
            SagaState.INITIATED,
            UUID.randomUUID(),
            now));

    PaymentContext ctx =
        new PaymentContext(
            caseId,
            "agent-buyer-smoke",
            "merchant-acme",
            new BigDecimal("18.00"),
            "USD",
            "monthly newsletter access",
            Map.of(
                "buyer.name", "Alice Buyer",
                "buyer.country", "US",
                "merchant.name", "Acme Widgets Ltd",
                "merchant.country", "US"));

    long start = System.currentTimeMillis();
    ComplianceVerdict v = complianceAgent.check(ctx);
    long elapsed = System.currentTimeMillis() - start;

    var row = verdictRepo.findAll().stream().findFirst().orElseThrow();
    System.out.println("=== SMOKE TEST RESULT ===");
    System.out.println("outcome: " + v.outcome());
    System.out.println("citations: " + v.citations());
    System.out.println("rationale: " + v.rationale());
    System.out.println("input_tokens: " + row.getInputTokens());
    System.out.println("output_tokens: " + row.getOutputTokens());
    System.out.println("cost_usd: " + row.getCostUsd());
    System.out.println("latency_ms: " + row.getLatencyMs());
    System.out.println("wall_clock_ms: " + elapsed);
    System.out.println("=== END SMOKE TEST RESULT ===");

    assertThat(v.outcome()).isEqualTo(Outcome.PASS);
    assertThat(v.citations()).isEmpty();
    assertThat(v.rationale()).isNotBlank();
    assertThat(elapsed).isLessThan(15_000L);
    assertThat(verdictRepo.count()).isPositive();
  }
}
