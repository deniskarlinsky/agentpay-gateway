package com.agentpay.orchestrator.e2e;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers + WireMock harness for the orchestrator E2E tests. The PSP is stubbed via
 * WireMock so the orchestrator's RestClient makes a REAL HTTP call to a real local port (B-11):
 * near-production fidelity, zero docker overhead beyond the database and broker.
 */
public abstract class IntegrationTestBase {

  @Container
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("agentpay")
          .withUsername("agentpay")
          .withPassword("agentpay")
          .withInitScript("init/01-init-test.sql");

  @Container protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.1");

  @org.junit.jupiter.api.extension.RegisterExtension
  protected static final WireMockExtension WIREMOCK =
      WireMockExtension.newInstance()
          .options(
              com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort())
          .build();

  static {
    POSTGRES.start();
    KAFKA.start();
  }

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("agentpay.mock-psp.url", () -> WIREMOCK.baseUrl());
    // Route both Anthropic and Voyage through the WireMock instance. Scenario tests register
    // additional Anthropic stubs in their test methods; the default Voyage stub below covers the
    // RouteMetricsSeed run at app startup (which happens before any test method body).
    r.add("spring.ai.anthropic.base-url", () -> WIREMOCK.baseUrl());
    r.add("spring.ai.anthropic.api-key", () -> "test-key-not-real");
    r.add("agentpay.voyage.base-url", () -> WIREMOCK.baseUrl());
    r.add("agentpay.voyage.api-key", () -> "test-voyage-key-not-real");
    r.add("agentpay.outbox.poll-interval-ms", () -> "50");

    // Side-effect: register a default Voyage stub so RouteMetricsSeed succeeds at startup. The
    // WireMockExtension's beforeAll lifecycle has already started its server by this point
    // (JUnit5 extension callbacks precede Spring's context customization). Per-test Anthropic
    // stubs are registered inside the test method body and take precedence on /v1/messages.
    AnthropicWireMockStubs.registerVoyageStub(WIREMOCK);
  }
}
