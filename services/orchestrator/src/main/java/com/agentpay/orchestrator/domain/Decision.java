package com.agentpay.orchestrator.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

/**
 * Aggregated decision-plane output (REQUIREMENTS.md §7.2.1). The Supervisor (Iter 4b.2) builds this
 * from the three specialist verdicts per FR-DP-002.
 *
 * <p>{@code route} is present when the RoutingAgent returned a recommendation — always on APPROVED,
 * usually on REVIEW (so a SUSPENDED_FOR_REVIEW case can resume to ROUTED on human GRANTED without
 * re-running the decision plane, Iter 4b.3 / FR-O-005), and empty on DECLINED.
 *
 * <p>{@code budgetExceeded} (Iter 6, NFR-COST-001): set to true when the Supervisor short-circuited
 * fan-out because the running per-case cost exceeded {@code agentpay.budget.per_case_usd}. The
 * PaymentSaga branches on this flag in the REVIEW path to publish {@code case.budget_exceeded}
 * instead of {@code human.approval.requested}. Existing decision_jsonb rows (Iter 4b.3) written
 * without the field deserialize as {@code false} — Jackson defaults absent boolean primitives.
 */
public record Decision(
    DecisionOutcome outcome,
    int riskScore,
    ComplianceVerdict compliance,
    Optional<RouteRecommendation> route,
    List<String> rationale,
    boolean budgetExceeded) {

  @JsonCreator
  public Decision(
      @JsonProperty("outcome") DecisionOutcome outcome,
      @JsonProperty("riskScore") int riskScore,
      @JsonProperty("compliance") ComplianceVerdict compliance,
      @JsonProperty("route") Optional<RouteRecommendation> route,
      @JsonProperty("rationale") List<String> rationale,
      @JsonProperty(value = "budgetExceeded", defaultValue = "false") boolean budgetExceeded) {
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
    this.outcome = outcome;
    this.riskScore = riskScore;
    this.compliance = compliance;
    this.route = route;
    this.rationale = rationale;
    this.budgetExceeded = budgetExceeded;
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
        DecisionOutcome.APPROVED, riskScore, compliance, Optional.of(route), rationale, false);
  }

  public static Decision declined(
      int riskScore, ComplianceVerdict compliance, List<String> rationale) {
    return new Decision(
        DecisionOutcome.DECLINED, riskScore, compliance, Optional.empty(), rationale, false);
  }

  public static Decision review(
      int riskScore, ComplianceVerdict compliance, List<String> rationale) {
    return new Decision(
        DecisionOutcome.REVIEW, riskScore, compliance, Optional.empty(), rationale, false);
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
        DecisionOutcome.REVIEW,
        riskScore,
        compliance,
        Optional.ofNullable(route),
        rationale,
        false);
  }

  /**
   * Budget short-circuit REVIEW (Iter 6, NFR-COST-001). The Supervisor returns this when the
   * running per-case cost exceeded the configured budget; PaymentSaga reads {@link
   * #budgetExceeded()} to publish {@code case.budget_exceeded} instead of the normal human-approval
   * request.
   */
  public static Decision budgetReview(
      int riskScore, ComplianceVerdict compliance, List<String> rationale) {
    return new Decision(
        DecisionOutcome.REVIEW, riskScore, compliance, Optional.empty(), rationale, true);
  }
}
