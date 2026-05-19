package com.agentpay.evals.runners;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Schema for one entry in {@code evals/golden_cases.json} (FR-E-001). Unknown fields are ignored so
 * the JSON can carry case-type-specific extensions (e.g. {@code mock_tool_evidence} for Compliance,
 * {@code mock_rag_candidates} for Routing) without forcing every case to declare them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenCase(
    @JsonProperty("case_id") String caseId,
    @JsonProperty("agent") String agent,
    @JsonProperty("expected_outcome") String expectedOutcome,
    @JsonProperty("expected_reason_class") String expectedReasonClass,
    @JsonProperty("payment_context") PaymentContext paymentContext,
    @JsonProperty("mock_tool_evidence") List<Map<String, Object>> mockToolEvidence,
    @JsonProperty("mock_rag_candidates") List<Map<String, Object>> mockRagCandidates,
    @JsonProperty("expected_citation_id") String expectedCitationId,
    @JsonProperty("expected_psp_id") String expectedPspId,
    @JsonProperty("pii_keys_to_check_redacted") List<String> piiKeysToCheckRedacted) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PaymentContext(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("agent_id") String agentId,
      @JsonProperty("merchant_id") String merchantId,
      @JsonProperty("amount") String amount,
      @JsonProperty("currency") String currency,
      @JsonProperty("description") String description,
      @JsonProperty("agent_metadata") Map<String, String> agentMetadata) {}
}
