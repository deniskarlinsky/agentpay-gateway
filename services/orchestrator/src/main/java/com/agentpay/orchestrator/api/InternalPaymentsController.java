package com.agentpay.orchestrator.api;

import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.saga.PaymentSaga;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/payments")
public class InternalPaymentsController {

  private final PaymentSaga saga;

  public InternalPaymentsController(PaymentSaga saga) {
    this.saga = saga;
  }

  @PostMapping
  public ResponseEntity<InternalPaymentResponse> create(@RequestBody InternalPaymentRequest req) {
    PaymentSaga.StartResult result =
        saga.start(
            new PaymentContext(
                req.caseId,
                req.agentId,
                req.merchantId,
                req.amount,
                req.currency,
                req.description,
                java.util.Map.of()),
            req.intentTokenJti);
    // FR-O-007 idempotency: HTTP 202 in both cases (new + duplicate). The `duplicate` flag in the
    // body distinguishes "we just started this" from "you already started this and here's where
    // it is" without changing HTTP semantics.
    return ResponseEntity.accepted()
        .body(
            new InternalPaymentResponse(
                result.caseId(), result.state().name(), result.duplicate()));
  }

  public static final class InternalPaymentRequest {
    @JsonProperty("case_id")
    public String caseId;

    @JsonProperty("agent_id")
    public String agentId;

    @JsonProperty("merchant_id")
    public String merchantId;

    @JsonProperty("amount")
    public BigDecimal amount;

    @JsonProperty("currency")
    public String currency;

    @JsonProperty("intent_token_jti")
    public UUID intentTokenJti;

    @JsonProperty("description")
    public String description;
  }

  public record InternalPaymentResponse(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("status") String status,
      @JsonProperty("duplicate") boolean duplicate) {}
}
