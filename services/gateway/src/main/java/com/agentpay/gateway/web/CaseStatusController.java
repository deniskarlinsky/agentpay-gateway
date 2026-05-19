package com.agentpay.gateway.web;

import com.agentpay.gateway.orchestrator.OrchestratorClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Iter 5 (REQUIREMENTS §7.1.3): public case-status lookup used by the buyer-client to poll a
 * submitted payment until it reaches a terminal Saga state. The body is the orchestrator response
 * forwarded verbatim; no authentication is required because the intent token is single-use and has
 * already been consumed at POST /payments time.
 */
@RestController
public class CaseStatusController {

  private final OrchestratorClient orchestrator;

  public CaseStatusController(OrchestratorClient orchestrator) {
    this.orchestrator = orchestrator;
  }

  @GetMapping("/cases/{caseId}")
  public ResponseEntity<OrchestratorClient.CaseStatus> get(@PathVariable String caseId) {
    return orchestrator
        .getCaseStatus(caseId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
