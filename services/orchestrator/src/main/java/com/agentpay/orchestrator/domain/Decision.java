package com.agentpay.orchestrator.domain;

/**
 * Iter 3 thin-shape Decision (stub supervisor). Iter 4 will fatten it with risk score, compliance
 * verdict, and full rationale per REQUIREMENTS.md §7.2.1.
 */
public record Decision(Outcome outcome, RouteRecommendation route, String rationale) {

  public enum Outcome {
    APPROVED,
    DECLINED,
    REVIEW
  }

  public record RouteRecommendation(String pspId, String routeId) {}
}
