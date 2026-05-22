package com.agentpay.orchestrator.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentpay.orchestrator.agents.ComplianceAgent;
import com.agentpay.orchestrator.agents.RiskAgent;
import com.agentpay.orchestrator.agents.RoutingAgent;
import com.agentpay.orchestrator.api.InternalCasesController.CaseTransitionsResponse;
import com.agentpay.orchestrator.api.InternalPaymentsController;
import com.agentpay.orchestrator.decision.Supervisor;
import com.agentpay.orchestrator.domain.ComplianceVerdict;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RouteRecommendation;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 4 (buyer-simulator-ux §5): the orchestrator-side endpoint that powers the UI's saga state
 * machine animation and per-agent verdict cards. Stubs the supervisor — agent invocation is
 * bypassed so verdicts will be empty, which is the contracted "field present, may be empty" shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import({NoOpMcpTestConfig.class, InternalCaseTransitionsEndpointIT.StubSupervisorConfig.class})
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration,"
          + "org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration,"
          + "org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration,"
          + "org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration"
    })
class InternalCaseTransitionsEndpointIT extends IntegrationTestBase {

  @LocalServerPort private int port;

  static final AtomicReference<Decision> NEXT_DECISION =
      new AtomicReference<>(
          Decision.approved(
              5,
              new ComplianceVerdict(Outcome.PASS, List.of(), "stub: clear"),
              new RouteRecommendation("psp-c", "route-us-1", 0.95f, 30, "stub"),
              List.of("stub")));

  @TestConfiguration
  static class StubSupervisorConfig {
    @Bean
    @Primary
    Supervisor stubSupervisor(RiskAgent risk, ComplianceAgent compliance, RoutingAgent routing) {
      return new Supervisor(risk, compliance, routing) {
        @Override
        public Decision decide(PaymentContext ctx) {
          return NEXT_DECISION.get();
        }
      };
    }
  }

  @Test
  void transitionsEndpointReturnsOrderedTimelineForKnownCase() {
    NEXT_DECISION.set(
        Decision.approved(
            5,
            new ComplianceVerdict(Outcome.PASS, List.of(), "stub: clear"),
            new RouteRecommendation("psp-c", "route-us-1", 0.95f, 30, "stub"),
            List.of("stub")));

    WIREMOCK.stubFor(
        post(urlPathEqualTo("/charge"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"case_id":"case-transitions-1","psp_id":"psp-c","success":true,
                         "auth_code":"AUTH-1","reason_code":null,"cost_bps":45}
                        """)));

    String caseId = "case-transitions-1";
    var body =
        Map.of(
            "case_id", caseId,
            "agent_id", "agent-buyer-001",
            "merchant_id", "merchant-acme",
            "amount", "42.50",
            "currency", "USD",
            "intent_token_jti", UUID.randomUUID().toString(),
            "description", "transitions endpoint",
            "agent_metadata",
                Map.of(
                    "buyer.name", "Alice Buyer", "buyer.country", "US", "merchant.country", "US"));

    RestClient client = RestClient.create();

    var postResp =
        client
            .post()
            .uri("http://localhost:" + port + "/internal/payments")
            .body(body)
            .retrieve()
            .body(InternalPaymentsController.InternalPaymentResponse.class);
    assertThat(postResp).isNotNull();
    assertThat(postResp.caseId()).isEqualTo(caseId);

    ResponseEntity<CaseTransitionsResponse> resp =
        client
            .get()
            .uri("http://localhost:" + port + "/internal/cases/{id}/transitions", caseId)
            .retrieve()
            .toEntity(CaseTransitionsResponse.class);
    assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    CaseTransitionsResponse out = resp.getBody();
    assertThat(out).isNotNull();
    assertThat(out.caseId()).isEqualTo(caseId);
    assertThat(out.currentState()).isEqualTo("COMMITTED");
    assertThat(out.transitions()).isNotEmpty();
    // First row is the INITIATED bootstrap — state_from is null per the schema (saga_transitions
    // allows null on the entry row).
    assertThat(out.transitions().get(0).stateFrom()).isNull();
    assertThat(out.transitions().get(0).stateTo()).isEqualTo("INITIATED");
    assertThat(out.transitions().get(0).createdAt()).isNotNull();
    // At least one downstream transition with non-null state_from.
    assertThat(out.transitions().size()).isGreaterThanOrEqualTo(2);
    assertThat(out.transitions().get(1).stateFrom()).isNotNull();
    // created_at is ordered ascending.
    OffsetDateTime prev = out.transitions().get(0).createdAt();
    for (int i = 1; i < out.transitions().size(); i++) {
      OffsetDateTime curr = out.transitions().get(i).createdAt();
      assertThat(curr).isAfterOrEqualTo(prev);
      prev = curr;
    }
    // Stub supervisor bypasses real agent invocation — verdicts list must be present (non-null).
    assertThat(out.verdicts()).isNotNull();
  }

  @Test
  void transitionsEndpointReturns404ForUnknownCase() {
    RestClient client = RestClient.create();
    try {
      client
          .get()
          .uri(
              "http://localhost:" + port + "/internal/cases/{id}/transitions",
              "case-does-not-exist")
          .retrieve()
          .toEntity(CaseTransitionsResponse.class);
      assertThat(false).as("expected 404 for unknown case").isTrue();
    } catch (RestClientResponseException e) {
      assertThat(e.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(404));
    }
  }
}
