package com.agentpay.orchestrator.domain;

import java.util.List;

/** ComplianceAgent verdict — FR-A-C-001. Wire-bound: field order/types are the LLM schema. */
public record ComplianceVerdict(Outcome outcome, List<String> citations, String rationale) {

  public ComplianceVerdict {
    if (outcome == null) {
      throw new IllegalArgumentException("outcome must be non-null");
    }
    if (citations == null) {
      throw new IllegalArgumentException(
          "citations must be non-null (empty list allowed for PASS)");
    }
    if (rationale == null || rationale.isBlank()) {
      throw new IllegalArgumentException("rationale must be non-blank");
    }
  }

  /** Sentinel returned by the Supervisor (Iter 4b.2) on ComplianceAgent timeout or error. */
  public static ComplianceVerdict review(String reason) {
    return new ComplianceVerdict(Outcome.REVIEW, List.of(), reason);
  }
}
