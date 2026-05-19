package com.agentpay.orchestrator.api;

import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.persistence.SagaTransitionEntity;
import com.agentpay.orchestrator.persistence.SagaTransitionRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Case status lookup for the gateway's /cases/{id} proxy (Iter 5, REQUIREMENTS §7.1.3). The
 * decision JSON is the same node persisted at REVIEW time; psp_outcome is derived from the terminal
 * saga_transition row (only COMMITTED/COMPENSATED carry it). Non-terminal states return
 * psp_outcome=null.
 */
@RestController
@RequestMapping("/internal/cases")
public class InternalCasesController {

  private final CaseRepository cases;
  private final SagaTransitionRepository transitions;
  private final String traceUrlTemplate;

  public InternalCasesController(
      CaseRepository cases,
      SagaTransitionRepository transitions,
      @Value("${agentpay.case.trace-url-template:http://localhost:3000/trace/{case_id}}")
          String traceUrlTemplate) {
    this.cases = cases;
    this.transitions = transitions;
    this.traceUrlTemplate = traceUrlTemplate;
  }

  @GetMapping("/{caseId}")
  @Transactional(value = "transactionManager", readOnly = true)
  public ResponseEntity<CaseStatusResponse> get(@PathVariable String caseId) {
    return cases
        .findById(caseId)
        .map(
            entity ->
                ResponseEntity.ok(
                    new CaseStatusResponse(
                        entity.getCaseId(),
                        entity.getState().name(),
                        entity.getDecisionJsonb(),
                        derivePspOutcome(entity),
                        traceUrlTemplate.replace("{case_id}", entity.getCaseId()))))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private PspOutcome derivePspOutcome(CaseEntity entity) {
    SagaState state = entity.getState();
    if (state == SagaState.COMMITTED) {
      return new PspOutcome("SUCCESS", null);
    }
    if (state == SagaState.COMPENSATED) {
      List<SagaTransitionEntity> all =
          transitions.findByCaseIdOrderByCreatedAtAsc(entity.getCaseId());
      String reason = all.isEmpty() ? null : all.get(all.size() - 1).getReason();
      return new PspOutcome("FAILED", reason);
    }
    return null;
  }

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record CaseStatusResponse(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("state") String state,
      @JsonProperty("decision") JsonNode decision,
      @JsonProperty("psp_outcome") PspOutcome pspOutcome,
      @JsonProperty("trace_url") String traceUrl) {}

  public record PspOutcome(
      @JsonProperty("status") String status, @JsonProperty("reason_code") String reasonCode) {}
}
