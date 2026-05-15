package com.agentpay.orchestrator.psp;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Thin RestClient wrapper around mock-psp's POST /charge. */
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
      String caseId, BigDecimal amount, String currency, String pspId, String routeId) {}

  public record ChargeResponse(
      String caseId,
      String pspId,
      boolean success,
      String authCode,
      String reasonCode,
      int costBps) {}
}
