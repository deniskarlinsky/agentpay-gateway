package com.agentpay.orchestrator.decision;

import com.agentpay.orchestrator.domain.ComplianceVerdict;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RouteRecommendation;
import java.util.List;
import org.springframework.stereotype.Service;

// TODO (Iter 4b.2, FR-DP-001..005): replace this stub with the real fan-out supervisor that calls
// RiskAgent, ComplianceAgent, RoutingAgent in parallel via virtual threads + CompletableFuture
// and aggregates per FR-DP-002. Until then, Iter 3/4b.1 unconditionally approves with a hardcoded
// psp-c route (highest success rate among the three mock-psp profiles → least flaky for tests).
@Service
public class Supervisor {

  static final RouteRecommendation STUB_ROUTE =
      new RouteRecommendation("psp-c", "route-stub-1", 0.978f, 45, "stub route");

  static final ComplianceVerdict STUB_COMPLIANCE =
      new ComplianceVerdict(
          Outcome.PASS, List.of(), "stub: real ComplianceAgent wires in Iter 4b.2");

  public Decision decide(PaymentContext ctx) {
    return Decision.approved(
        0,
        STUB_COMPLIANCE,
        STUB_ROUTE,
        List.of("stub: Iter 4b.2 will replace this with a real fan-out supervisor"));
  }
}
