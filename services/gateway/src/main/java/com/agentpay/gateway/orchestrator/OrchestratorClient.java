package com.agentpay.gateway.orchestrator;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Forwards accepted payments to the orchestrator's internal endpoint (FR-O-* entry point). */
@Component
public class OrchestratorClient {

  private static final Logger log = LoggerFactory.getLogger(OrchestratorClient.class);

  private final RestClient restClient;

  public OrchestratorClient(@Value("${agentpay.orchestrator.url}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public Response forward(Request request) {
    try {
      return restClient
          .post()
          .uri("/internal/payments")
          .body(request)
          .retrieve()
          .body(Response.class);
    } catch (RestClientResponseException e) {
      log.warn(
          "orchestrator returned non-2xx case_id={} status={}",
          request.caseId(),
          e.getStatusCode());
      throw new OrchestratorUnavailableException(
          "orchestrator returned " + e.getStatusCode().value());
    } catch (ResourceAccessException e) {
      log.warn("orchestrator unreachable case_id={} error={}", request.caseId(), e.getMessage());
      throw new OrchestratorUnavailableException("orchestrator unreachable: " + e.getMessage());
    }
  }

  /**
   * Iter 5 (§7.1.3): proxy GET for case status. Empty Optional → upstream 404 (case not found);
   * other non-2xx → OrchestratorUnavailableException.
   */
  public Optional<CaseStatus> getCaseStatus(String caseId) {
    try {
      CaseStatus body =
          restClient
              .get()
              .uri("/internal/cases/{caseId}", caseId)
              .retrieve()
              .body(CaseStatus.class);
      return Optional.ofNullable(body);
    } catch (RestClientResponseException e) {
      HttpStatusCode code = e.getStatusCode();
      if (code.value() == 404) {
        return Optional.empty();
      }
      log.warn("orchestrator case-status non-2xx case_id={} status={}", caseId, code);
      throw new OrchestratorUnavailableException("orchestrator returned " + code.value());
    } catch (ResourceAccessException e) {
      log.warn("orchestrator unreachable case_id={} error={}", caseId, e.getMessage());
      throw new OrchestratorUnavailableException("orchestrator unreachable: " + e.getMessage());
    }
  }

  /**
   * Task 4: proxy GET for case transitions + agent verdicts. Same empty/non-2xx semantics as {@link
   * #getCaseStatus}.
   */
  public Optional<CaseTransitions> getCaseTransitions(String caseId) {
    try {
      CaseTransitions body =
          restClient
              .get()
              .uri("/internal/cases/{caseId}/transitions", caseId)
              .retrieve()
              .body(CaseTransitions.class);
      return Optional.ofNullable(body);
    } catch (RestClientResponseException e) {
      HttpStatusCode code = e.getStatusCode();
      if (code.value() == 404) {
        return Optional.empty();
      }
      log.warn("orchestrator case-transitions non-2xx case_id={} status={}", caseId, code);
      throw new OrchestratorUnavailableException("orchestrator returned " + code.value());
    } catch (ResourceAccessException e) {
      log.warn("orchestrator unreachable case_id={} error={}", caseId, e.getMessage());
      throw new OrchestratorUnavailableException("orchestrator unreachable: " + e.getMessage());
    }
  }

  public record Request(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("agent_id") String agentId,
      @JsonProperty("merchant_id") String merchantId,
      @JsonProperty("amount") BigDecimal amount,
      @JsonProperty("currency") String currency,
      @JsonProperty("intent_token_jti") UUID intentTokenJti,
      @JsonProperty("description") String description,
      // Iter 5: identity-namespaced metadata (never PII; redacted upstream by PiiRedactionFilter).
      @JsonProperty("agent_metadata") Map<String, String> agentMetadata) {}

  public record Response(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("status") String status,
      @JsonProperty("duplicate") boolean duplicate) {}

  /** Mirrors orchestrator's CaseStatusResponse (Iter 5, §7.1.3). Returned as-is by gateway. */
  public record CaseStatus(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("state") String state,
      @JsonProperty("decision") JsonNode decision,
      @JsonProperty("psp_outcome") JsonNode pspOutcome,
      @JsonProperty("trace_url") String traceUrl) {}

  /** Task 4: mirrors orchestrator's CaseTransitionsResponse (buyer-simulator-ux §5). */
  public record CaseTransitions(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("current_state") String currentState,
      @JsonProperty("transitions") List<Transition> transitions,
      @JsonProperty("verdicts") List<Verdict> verdicts) {}

  public record Transition(
      @JsonProperty("state_from") String stateFrom,
      @JsonProperty("state_to") String stateTo,
      @JsonProperty("reason") String reason,
      @JsonProperty("created_at") OffsetDateTime createdAt) {}

  public record Verdict(
      @JsonProperty("agent_name") String agentName,
      @JsonProperty("model") String model,
      @JsonProperty("verdict_json") JsonNode verdictJson,
      @JsonProperty("latency_ms") int latencyMs,
      @JsonProperty("cost_usd") BigDecimal costUsd,
      @JsonProperty("created_at") OffsetDateTime createdAt) {}
}
