package com.agentpay.orchestrator.domain;

/** RoutingAgent verdict — FR-A-RT-001. Wire-bound: the field order/types are the LLM schema. */
public record RouteRecommendation(
    String pspId,
    String routeId,
    float expectedSuccessRate,
    int expectedCostBps,
    String rationale) {

  public RouteRecommendation {
    if (pspId == null || pspId.isBlank()) {
      throw new IllegalArgumentException("pspId must be non-blank");
    }
    if (routeId == null || routeId.isBlank()) {
      throw new IllegalArgumentException("routeId must be non-blank");
    }
    if (expectedSuccessRate < 0.0f || expectedSuccessRate > 1.0f) {
      throw new IllegalArgumentException(
          "expectedSuccessRate must be in [0.0, 1.0], got " + expectedSuccessRate);
    }
    if (expectedCostBps < 0) {
      throw new IllegalArgumentException(
          "expectedCostBps must be non-negative, got " + expectedCostBps);
    }
    if (rationale == null || rationale.isBlank()) {
      throw new IllegalArgumentException("rationale must be non-blank");
    }
  }
}
