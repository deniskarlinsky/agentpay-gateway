package com.agentpay.orchestrator.psp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin RestClient wrapper around mock-psp's POST /charge.
 *
 * <p>The wire contract is snake_case on both directions; see {@code
 * services/mock-psp/src/main/java/com/agentpay/mockpsp/Charge*.java}. The {@code @JsonProperty}
 * annotations below are LOAD-BEARING — without them Jackson would emit camelCase keys ({@code
 * caseId}, {@code pspId}, ...) and mock-psp would receive {@code pspId=null}, look up a missing
 * profile, and reply success=false — landing every payment in COMPENSATED instead of COMMITTED.
 * Iter 5 hotfix; scenario tests used WireMock without body matching, so this never surfaced until
 * the live demo. The MockPspWireContractIT regression locks it down.
 */
@Component
public class MockPspClient {

  private static final Logger log = LoggerFactory.getLogger(MockPspClient.class);

  private final RestClient restClient;

  public MockPspClient(
      RestClient.Builder builder, @Value("${agentpay.mock-psp.url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  public ChargeResponse charge(ChargeRequest request) {
    try {
      return restClient.post().uri("/charge").body(request).retrieve().body(ChargeResponse.class);
    } catch (RestClientResponseException e) {
      log.warn(
          "mock-psp returned non-2xx case_id={} status={}", request.caseId(), e.getStatusCode());
      return new ChargeResponse(request.caseId(), request.pspId(), false, null, "AC01", 0);
    }
  }

  public record ChargeRequest(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("amount") BigDecimal amount,
      @JsonProperty("currency") String currency,
      @JsonProperty("psp_id") String pspId,
      @JsonProperty("route_id") String routeId) {}

  public record ChargeResponse(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("psp_id") String pspId,
      @JsonProperty("success") boolean success,
      @JsonProperty("auth_code") String authCode,
      @JsonProperty("reason_code") String reasonCode,
      @JsonProperty("cost_bps") int costBps) {}
}
