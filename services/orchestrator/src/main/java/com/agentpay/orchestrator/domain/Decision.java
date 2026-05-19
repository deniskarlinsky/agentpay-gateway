package com.agentpay.orchestrator.domain;

import java.util.List;
import java.util.Optional;

/**
 * Aggregated decision-plane output (REQUIREMENTS.md §7.2.1). The Supervisor (Iter 4b.2) builds this
 * from the three specialist verdicts per FR-DP-002.
 *
 * <p>{@code route} is present when the RoutingAgent returned a recommendation — always on APPROVED,
 * usually on REVIEW (so a SUSPENDED_FOR_REVIEW case can resume to ROUTED on human GRANTED without
 * re-running the decision plane, Iter 4b.3 / FR-O-005), and empty on DECLINED.
 */
public record Decision(
    DecisionOutcome outcome,
    int riskScore,
    ComplianceVerdict compliance,
    Optional<RouteRecommendation> route,
    List<String> rationale) {

  public Decision {
    if (outcome == null) {
      throw new IllegalArgumentException("outcome must be non-null");
    }
    if (riskScore < 0 || riskScore > 100) {
      throw new IllegalArgumentException("riskScore must be in [0, 100], got " + riskScore);
    }
    if (compliance == null) {
      throw new IllegalArgumentException("compliance must be non-null");
    }
    if (route == null) {
      throw new IllegalArgumentException("route must be non-null (use Optional.empty)");
    }
    if (rationale == null) {
      throw new IllegalArgumentException("rationale must be non-null (empty list allowed)");
    }
  }

  /**
   * Aggregation factory used by Supervisor (Iter 4b.2). The FR-DP-002 rule itself is intentionally
   * NOT implemented here — Supervisor owns that branch logic and constructs the Decision via the
   * appropriate static factory.
   */
  public static Decision approved(
      int riskScore,
      ComplianceVerdict compliance,
      RouteRecommendation route,
      List<String> rationale) {
    return new Decision(
        DecisionOutcome.APPROVED, riskScore, compliance, Optional.of(route), rationale);
  }

  public static Decision declined(
      int riskScore, ComplianceVerdict compliance, List<String> rationale) {
    return new Decision(
        DecisionOutcome.DECLINED, riskScore, compliance, Optional.empty(), rationale);
  }

  public static Decision review(
      int riskScore, ComplianceVerdict compliance, List<String> rationale) {
    return new Decision(DecisionOutcome.REVIEW, riskScore, compliance, Optional.empty(), rationale);
  }

  /**
   * REVIEW variant that preserves a computed routing recommendation. Used by the Supervisor when
   * the risk score or compliance verdict drives REVIEW but the RoutingAgent still produced a route;
   * the orchestrator stores the route alongside the Decision so a human GRANTED response can resume
   * through ROUTED → commit without re-calling the routing agent (Iter 4b.3 / FR-O-005).
   */
  public static Decision review(
      int riskScore,
      ComplianceVerdict compliance,
      RouteRecommendation route,
      List<String> rationale) {
    return new Decision(
        DecisionOutcome.REVIEW, riskScore, compliance, Optional.ofNullable(route), rationale);
  }
}
