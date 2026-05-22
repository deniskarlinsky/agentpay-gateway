package com.agentpay.orchestrator.api;

import com.agentpay.orchestrator.agents.support.AgentVerdict;
import com.agentpay.orchestrator.agents.support.AgentVerdictRepository;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.persistence.SagaTransitionEntity;
import com.agentpay.orchestrator.persistence.SagaTransitionRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
  private final AgentVerdictRepository verdicts;
  private final ObjectMapper objectMapper;
  private final String traceUrlTemplate;

  public InternalCasesController(
      CaseRepository cases,
      SagaTransitionRepository transitions,
      AgentVerdictRepository verdicts,
      ObjectMapper objectMapper,
      @Value("${agentpay.case.trace-url-template:http://localhost:3000/trace/{case_id}}")
          String traceUrlTemplate) {
    this.cases = cases;
    this.transitions = transitions;
    this.verdicts = verdicts;
    this.objectMapper = objectMapper;
    this.traceUrlTemplate = traceUrlTemplate;
  }

  @GetMapping("/{caseId}")
  @Transactional(readOnly = true)
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

  @GetMapping("/{caseId}/transitions")
  @Transactional(readOnly = true)
  public ResponseEntity<CaseTransitionsResponse> getTransitions(@PathVariable String caseId) {
    return cases
        .findById(caseId)
        .map(
            entity -> {
              List<TransitionDto> tx =
                  transitions.findByCaseIdOrderByCreatedAtAsc(caseId).stream()
                      .map(
                          row ->
                              new TransitionDto(
                                  row.getStateFrom() == null ? null : row.getStateFrom().name(),
                                  row.getStateTo().name(),
                                  row.getReason(),
                                  row.getCreatedAt()))
                      .toList();
              List<VerdictDto> vd =
                  verdicts.findByCaseIdOrderByCreatedAtAsc(caseId).stream()
                      .map(this::toVerdictDto)
                      .toList();
              return ResponseEntity.ok(
                  new CaseTransitionsResponse(
                      entity.getCaseId(), entity.getState().name(), tx, vd));
            })
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private VerdictDto toVerdictDto(AgentVerdict v) {
    // verdict_json is JSON the orchestrator itself wrote; a parse failure is a bug, not a runtime
    // condition to handle gracefully.
    JsonNode node;
    try {
      node = objectMapper.readTree(v.getVerdictJson());
    } catch (IOException e) {
      throw new UncheckedIOException("invalid verdict_json for verdict id=" + v.getId(), e);
    }
    return new VerdictDto(
        v.getAgentName(), v.getModel(), node, v.getLatencyMs(), v.getCostUsd(), v.getCreatedAt());
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

  /** Task 4 (buyer-simulator-ux §5): saga timeline + per-agent verdicts for the UI. */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record CaseTransitionsResponse(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("current_state") String currentState,
      @JsonProperty("transitions") List<TransitionDto> transitions,
      @JsonProperty("verdicts") List<VerdictDto> verdicts) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record TransitionDto(
      @JsonProperty("state_from") String stateFrom,
      @JsonProperty("state_to") String stateTo,
      @JsonProperty("reason") String reason,
      @JsonProperty("created_at") OffsetDateTime createdAt) {}

  public record VerdictDto(
      @JsonProperty("agent_name") String agentName,
      @JsonProperty("model") String model,
      @JsonProperty("verdict_json") JsonNode verdictJson,
      @JsonProperty("latency_ms") int latencyMs,
      @JsonProperty("cost_usd") BigDecimal costUsd,
      @JsonProperty("created_at") OffsetDateTime createdAt) {}
}
