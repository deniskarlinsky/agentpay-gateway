package com.agentpay.orchestrator.domain;

import java.util.List;

/** RiskAgent verdict — FR-A-R-001. Wire-bound: field order/types are the LLM schema. */
public record RiskAssessment(int score, List<String> signals, String rationale) {

  public RiskAssessment {
    if (score < 0 || score > 100) {
      throw new IllegalArgumentException("score must be in [0, 100], got " + score);
    }
    if (signals == null) {
      throw new IllegalArgumentException("signals must be non-null (empty list allowed)");
    }
    if (rationale == null || rationale.isBlank()) {
      throw new IllegalArgumentException("rationale must be non-blank");
    }
  }

  /**
   * Sentinel returned by the Supervisor (Iter 4b.2) when the RiskAgent path errors or times out;
   * surfaced through FR-DP-002's REVIEW band.
   */
  public static RiskAssessment review(String reason) {
    return new RiskAssessment(50, List.of("agent:error"), reason);
  }
}
