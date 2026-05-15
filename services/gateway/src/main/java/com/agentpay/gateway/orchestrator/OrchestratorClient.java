package com.agentpay.gateway.orchestrator;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

  public record Request(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("agent_id") String agentId,
      @JsonProperty("merchant_id") String merchantId,
      @JsonProperty("amount") BigDecimal amount,
      @JsonProperty("currency") String currency,
      @JsonProperty("intent_token_jti") UUID intentTokenJti,
      @JsonProperty("description") String description) {}

  public record Response(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("status") String status,
      @JsonProperty("duplicate") boolean duplicate) {}
}
