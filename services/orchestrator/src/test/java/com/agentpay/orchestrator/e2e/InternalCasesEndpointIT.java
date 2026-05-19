package com.agentpay.orchestrator.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentpay.orchestrator.agents.ComplianceAgent;
import com.agentpay.orchestrator.agents.RiskAgent;
import com.agentpay.orchestrator.agents.RoutingAgent;
import com.agentpay.orchestrator.api.InternalCasesController.CaseStatusResponse;
import com.agentpay.orchestrator.api.InternalPaymentsController;
import com.agentpay.orchestrator.decision.Supervisor;
import com.agentpay.orchestrator.domain.ComplianceVerdict;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RouteRecommendation;
import java.util.HashMap;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression for the live-demo Hotfix A 404: a buyer-client that POSTs a payment and immediately
 * polls {@code GET /cases/{id}} must see the case, even though the gateway-side request returns
 * (state=SUSPENDED_FOR_REVIEW or terminal) only after the orchestrator's saga has stepped through
 * INITIATED → ... . With Scenario A's WireMock fixtures the supervisor decided in milliseconds and
 * masked any race; this test stubs the supervisor and pins the failure mode at the controller +
 * persistence boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import({NoOpMcpTestConfig.class, InternalCasesEndpointIT.StubSupervisorConfig.class})
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration,"
          + "org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration,"
          + "org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration,"
          + "org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration"
    })
class InternalCasesEndpointIT extends IntegrationTestBase {

  @LocalServerPort private int port;

  static final AtomicReference<Decision> NEXT_DECISION =
      new AtomicReference<>(
          Decision.approved(
              5,
              new ComplianceVerdict(Outcome.PASS, List.of(), "stub: clear"),
              new RouteRecommendation("psp-c", "route-us-1", 0.95f, 30, "stub"),
              List.of("stub")));

  /**
   * Real supervisor would call live LLMs; the stub returns whatever {@link #NEXT_DECISION} is set
   * to so individual test methods can exercise APPROVED, REVIEW (live-demo mode), or DECLINED.
   */
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
  void reviewPathStillExposesCaseImmediately() {
    // Mirrors the live-demo Scenario A failure mode: live LLMs fall back to REVIEW after
    // BeanOutputConverter rejects the prose response. The saga should park the case at
    // SUSPENDED_FOR_REVIEW and GET /internal/cases/{id} must return 200.
    NEXT_DECISION.set(
        Decision.review(
            55,
            new ComplianceVerdict(Outcome.REVIEW, List.of(), "stub: review"),
            List.of("stub review")));

    String caseId = "case-review-immediate";
    Map<String, Object> body = new HashMap<>();
    body.put("case_id", caseId);
    body.put("agent_id", "agent-buyer-001");
    body.put("merchant_id", "merchant-acme");
    body.put("amount", "42.50");
    body.put("currency", "USD");
    body.put("intent_token_jti", UUID.randomUUID().toString());
    body.put("description", "hotfix A review regression");
    body.put(
        "agent_metadata",
        Map.of("buyer.name", "Risky Reviewer", "buyer.country", "GB", "merchant.country", "US"));

    RestClient client = RestClient.create();
    var postResp =
        client
            .post()
            .uri("http://localhost:" + port + "/internal/payments")
            .body(body)
            .retrieve()
            .body(InternalPaymentsController.InternalPaymentResponse.class);
    assertThat(postResp).isNotNull();
    assertThat(postResp.status()).isEqualTo("SUSPENDED_FOR_REVIEW");

    ResponseEntity<CaseStatusResponse> getResp =
        client
            .get()
            .uri("http://localhost:" + port + "/internal/cases/{id}", caseId)
            .retrieve()
            .toEntity(CaseStatusResponse.class);
    assertThat(getResp.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(getResp.getBody()).isNotNull();
    assertThat(getResp.getBody().caseId()).isEqualTo(caseId);
    assertThat(getResp.getBody().state()).isEqualTo("SUSPENDED_FOR_REVIEW");
  }

  @Test
  void postThenImmediateGetSeesTheCase() {
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
                        {"case_id":"case-getlookup","psp_id":"psp-c","success":true,
                         "auth_code":"AUTH-1","reason_code":null,"cost_bps":45}
                        """)));

    String caseId = "case-getlookup";
    var body =
        Map.of(
            "case_id", caseId,
            "agent_id", "agent-buyer-001",
            "merchant_id", "merchant-acme",
            "amount", "42.50",
            "currency", "USD",
            "intent_token_jti", UUID.randomUUID().toString(),
            "description", "hotfix A regression",
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

    // The bug surfaces here. POST has returned with a non-null state; the GET on the same caseId
    // must succeed without polling/awaitility — by the time start() returned, createInitiated had
    // committed and every subsequent saga transition is in its own committed JPA tx.
    ResponseEntity<CaseStatusResponse> getResp =
        client
            .get()
            .uri("http://localhost:" + port + "/internal/cases/{id}", caseId)
            .retrieve()
            .toEntity(CaseStatusResponse.class);
    assertThat(getResp.getStatusCode().is2xxSuccessful())
        .as("GET /internal/cases/%s must not 404 immediately after POST", caseId)
        .isTrue();
    assertThat(getResp.getBody()).isNotNull();
    assertThat(getResp.getBody().caseId()).isEqualTo(caseId);
    assertThat(getResp.getBody().state()).isEqualTo("COMMITTED");
    assertThat(getResp.getBody().traceUrl()).contains(caseId);
  }
}
