package com.agentpay.gateway.web;

import com.agentpay.gateway.orchestrator.OrchestratorClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 4 (buyer-simulator-ux §5): public read of the saga timeline + per-agent verdicts. Powers the
 * UI's state machine animation and verdict cards. Same security stance as {@link
 * CaseStatusController} — no auth required, the intent token was consumed at POST /payments.
 */
@RestController
public class CaseTransitionsController {

  private final OrchestratorClient orchestrator;

  public CaseTransitionsController(OrchestratorClient orchestrator) {
    this.orchestrator = orchestrator;
  }

  @GetMapping("/cases/{caseId}/transitions")
  public ResponseEntity<OrchestratorClient.CaseTransitions> get(@PathVariable String caseId) {
    return orchestrator
        .getCaseTransitions(caseId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
