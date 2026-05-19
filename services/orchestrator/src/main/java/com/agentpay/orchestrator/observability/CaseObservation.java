package com.agentpay.orchestrator.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Opens the per-case root Micrometer Observation (NFR-O-003) and exposes it to PaymentSaga.start()
 * via a small SAM. Child observations created by Spring AI's ChatClient instrumentation see this
 * one as their parent because {@link Observation#observe(Supplier)} installs it on the current
 * thread for the duration of the work — and the Supervisor's virtual-thread fan-out uses {@link
 * io.micrometer.context.ContextSnapshot} to carry that context into each child task.
 *
 * <p>Span attributes follow the {@code agentpay.case.*} naming required by NFR-O-003. Micrometer
 * key-values are strings; the OTel exporter renders them as span attributes verbatim, so the cost
 * is written as {@code BigDecimal.toPlainString()} (no scientific notation) — Langfuse and the file
 * exporter both display the unmodified value.
 *
 * <p>SCOPE LIMITATION (deliberate, Iter 6 MVP): the observation covers only the synchronous prefix
 * of a case's lifecycle that runs inside one Saga driver call. When a case suspends for human
 * review and later resumes via {@code PaymentSaga.onApprovalGranted}, the resume opens a
 * <em>separate trace</em>; correlation via {@code case_id} is left to a future iteration. This is
 * intentional — extending the span across the suspend/resume gap would require a span-store +
 * recovery hook out of scope for the MVP. The decision is documented here so reviewers don't
 * mistake the two-traces-per-reviewed-case shape for an accidental bug.
 */
@Component
public class CaseObservation {

  static final String CASE_OBSERVATION_NAME = "agentpay.case";

  private final ObservationRegistry registry;

  public CaseObservation(ObservationRegistry registry) {
    this.registry = registry;
  }

  /**
   * Wraps a unit of synchronous saga work. The supplier produces the {@link Outcome} that the
   * caller wants to return; this method writes the {@code agentpay.case.cost_usd} and {@code
   * agentpay.case.terminal_state} attributes from the outcome before closing the span.
   */
  public <T> T observe(
      String caseId, String agentId, String merchantId, Supplier<Outcome<T>> work) {
    Observation obs =
        Observation.createNotStarted(CASE_OBSERVATION_NAME, registry)
            .lowCardinalityKeyValue("agentpay.case.agent_id", agentId)
            .lowCardinalityKeyValue("agentpay.case.merchant_id", merchantId)
            // case_id is per-request (high-cardinality); keep it off the metric tag set.
            .highCardinalityKeyValue("agentpay.case.id", caseId);
    return obs.observe(
        () -> {
          Outcome<T> outcome = work.get();
          obs.lowCardinalityKeyValue("agentpay.case.terminal_state", outcome.terminalState());
          BigDecimal cost = outcome.costUsd();
          obs.highCardinalityKeyValue(
              "agentpay.case.cost_usd", cost == null ? "0" : cost.toPlainString());
          return outcome.value();
        });
  }

  /** Carrier struct so the caller can hand back both the return value and the span attributes. */
  public record Outcome<T>(T value, String terminalState, BigDecimal costUsd) {}
}
