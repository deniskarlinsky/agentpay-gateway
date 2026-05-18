package com.agentpay.orchestrator.domain;

/** Terminal decision-plane outcome consumed by the Saga (FR-DP-002, FR-O-004..006). */
public enum DecisionOutcome {
  APPROVED,
  DECLINED,
  REVIEW
}
