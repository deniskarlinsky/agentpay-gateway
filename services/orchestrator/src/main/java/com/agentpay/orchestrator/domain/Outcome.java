package com.agentpay.orchestrator.domain;

/**
 * Compliance-verdict outcome. ComplianceAgent emits only PASS or FAIL per its prompt; REVIEW is
 * reserved for the Supervisor's aggregation rule (FR-DP-002) so the value is still declared.
 */
public enum Outcome {
  PASS,
  FAIL,
  REVIEW
}
