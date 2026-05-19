package com.agentpay.orchestrator.agents.routing;

/**
 * One row of routing-metrics RAG (FR-A-RT-002). Field set is the contract the routing.md prompt was
 * written against — every field here is referenced by name in the few-shot examples, so the runtime
 * rendering in {@link RouteMetricsRetriever#renderAsKeyValueBlock} must keep the field set in step.
 */
public record RouteCandidate(
    String pspId,
    String routeId,
    double expectedSuccessRate,
    int expectedCostBps,
    int sampleSize,
    String observedAt,
    String notes) {}
