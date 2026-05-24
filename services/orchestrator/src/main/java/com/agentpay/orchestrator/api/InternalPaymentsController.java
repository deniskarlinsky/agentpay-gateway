package com.agentpay.orchestrator.api;

import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.saga.PaymentSaga;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/payments")
public class InternalPaymentsController {

  private final PaymentSaga saga;
  // ObjectProvider (not Tracer directly) so the @WebMvcTest slice — which doesn't load tracing
  // autoconfig — can still instantiate this controller; resolves the real Tracer at runtime.
  private final ObjectProvider<Tracer> tracerProvider;

  public InternalPaymentsController(PaymentSaga saga, ObjectProvider<Tracer> tracerProvider) {
    this.saga = saga;
    this.tracerProvider = tracerProvider;
  }

  @PostMapping
  public ResponseEntity<InternalPaymentResponse> create(@RequestBody InternalPaymentRequest req) {
    tagCaseAsLangfuseSession(req.caseId);
    Map<String, String> metadata = req.agentMetadata == null ? Map.of() : req.agentMetadata;
    PaymentSaga.StartResult result =
        saga.start(
            new PaymentContext(
                req.caseId,
                req.agentId,
                req.merchantId,
                req.amount,
                req.currency,
                req.description,
                metadata),
            req.intentTokenJti);
    // FR-O-007 idempotency: HTTP 202 in both cases (new + duplicate). The `duplicate` flag in the
    // body distinguishes "we just started this" from "you already started this and here's where
    // it is" without changing HTTP semantics.
    return ResponseEntity.accepted()
        .body(
            new InternalPaymentResponse(
                result.caseId(), result.state().name(), result.duplicate()));
  }

  /**
   * Surfaces the case_id at the trace level so the case trace is findable in Langfuse. Langfuse
   * (3.33) reads {@code langfuse.session.id} only from the trace's ROOT span; here that root is the
   * auto-instrumented HTTP server span, which is the current span at controller entry. Without
   * this, the agent/LLM spans are nested under a trace named only by the HTTP route and the case_id
   * lives solely as a high-cardinality child-span attribute, so a Langfuse search by case_id finds
   * nothing. (Langfuse 3.33 ignores {@code langfuse.trace.name}, so the trace name stays the route;
   * the session id is the working findability hook.)
   */
  private void tagCaseAsLangfuseSession(String caseId) {
    if (caseId == null) {
      return;
    }
    Tracer tracer = tracerProvider.getIfAvailable();
    if (tracer == null) {
      return;
    }
    Span span = tracer.currentSpan();
    if (span != null) {
      span.tag("langfuse.session.id", caseId);
    }
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

    /** Identity-namespaced fields (buyer.name, buyer.country, merchant.name, ...). Never PII. */
    @JsonProperty("agent_metadata")
    public Map<String, String> agentMetadata;
  }

  public record InternalPaymentResponse(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("status") String status,
      @JsonProperty("duplicate") boolean duplicate) {}
}
