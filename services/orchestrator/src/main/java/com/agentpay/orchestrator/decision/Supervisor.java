package com.agentpay.orchestrator.decision;

import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.PaymentContext;
import org.springframework.stereotype.Service;

// TODO (Iter 4, FR-DP-001..005): replace this stub with the real fan-out supervisor that calls
// RiskAgent, ComplianceAgent, RoutingAgent in parallel via virtual threads + CompletableFuture
// and aggregates per FR-DP-002. Until then, Iter 3 unconditionally approves with a hardcoded
// psp-c route (highest success rate among the three mock-psp profiles → least flaky for tests).
@Service
public class Supervisor {

  static final Decision.RouteRecommendation STUB_ROUTE =
      new Decision.RouteRecommendation("psp-c", "route-stub-1");

  public Decision decide(PaymentContext ctx) {
    return new Decision(
        Decision.Outcome.APPROVED,
        STUB_ROUTE,
        "stub: Iter 4 will replace this with a real fan-out supervisor");
  }
}
