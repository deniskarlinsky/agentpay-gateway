package com.agentpay.buyer.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper around the gateway's three public endpoints used by the buyer CLI flow: POST
 * /intent-tokens, POST /payments, GET /cases/{id}. All bodies match the API contracts in
 * REQUIREMENTS §7.1.1–7.1.3.
 */
public final class GatewayClient {

  private final RestClient restClient;

  public GatewayClient(String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public IntentTokenResponse requestIntentToken(IntentTokenRequest request) {
    return restClient
        .post()
        .uri("/intent-tokens")
        .body(request)
        .retrieve()
        .body(IntentTokenResponse.class);
  }

  public PaymentResponse submitPayment(String bearer, PaymentRequest request) {
    return restClient
        .post()
        .uri("/payments")
        .header("Authorization", "Bearer " + bearer)
        .body(request)
        .retrieve()
        .body(PaymentResponse.class);
  }

  public CaseStatus getCaseStatus(String caseId) {
    return restClient.get().uri("/cases/{caseId}", caseId).retrieve().body(CaseStatus.class);
  }

  public record IntentTokenRequest(
      @JsonProperty("agent_id") String agentId,
      @JsonProperty("agent_pubkey") String agentPubkey,
      @JsonProperty("merchant_id") String merchantId,
      @JsonProperty("amount_cap") BigDecimal amountCap,
      @JsonProperty("currency") String currency,
      @JsonProperty("scope") String scope,
      @JsonProperty("ttl_seconds") int ttlSeconds) {}

  public record IntentTokenResponse(
      @JsonProperty("intent_token") String intentToken,
      @JsonProperty("expires_at") Instant expiresAt) {}

  public record PaymentRequest(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("merchant_id") String merchantId,
      @JsonProperty("amount") BigDecimal amount,
      @JsonProperty("currency") String currency,
      @JsonProperty("description") String description,
      @JsonProperty("buyer_signature") String buyerSignature,
      @JsonProperty("agent_metadata") Map<String, String> agentMetadata) {}

  public record PaymentResponse(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("status") String status,
      @JsonProperty("trace_url") String traceUrl) {}

  public record CaseStatus(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("state") String state,
      @JsonProperty("decision") JsonNode decision,
      @JsonProperty("psp_outcome") JsonNode pspOutcome,
      @JsonProperty("trace_url") String traceUrl) {}
}
