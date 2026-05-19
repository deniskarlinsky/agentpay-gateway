package com.agentpay.evals.runners;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Deterministic checks over the agent's raw verdict (FR-E-003.1). Asserts (1) the outcome matches
 * the golden expectation, (2) PII metadata fields the case flagged for redaction never landed on
 * the rendered user message that went to the model, (3) Compliance cases that expect a sanctions
 * citation actually carry the matching {@code citationId}, (4) Routing cases pick the expected
 * {@code pspId}. Returns {@code (pass, note)} so the runner can include a short reason on failure
 * in the result JSON.
 */
public final class DeterministicJudge {

  private DeterministicJudge() {}

  public static Result judge(GoldenCase c, JsonNode rawVerdict, String renderedPromptUserMessage) {
    // (2) PII redaction: ensure metadata values flagged for redaction never reached the model
    // input. The PiiRedactionAdvisor in the orchestrator masks values inside the ChatClient
    // request; for the eval runner, we render with the same renderer and assert the masked
    // string never appears verbatim in the rendered user message.
    if (c.piiKeysToCheckRedacted() != null) {
      for (String key : c.piiKeysToCheckRedacted()) {
        String value = c.paymentContext().agentMetadata().get(key);
        if (value != null && renderedPromptUserMessage.contains(value)) {
          return new Result(false, "PII leak: '" + key + "' present in rendered prompt");
        }
      }
    }

    // (1, 3, 4) Outcome / citation / pspId match per agent type.
    switch (c.agent()) {
      case "risk" -> {
        int score = rawVerdict.path("score").asInt(-1);
        String expected = c.expectedOutcome();
        // Map score to the FR-DP-002 band the supervisor would aggregate into.
        String observed = score >= 80 ? "DECLINED" : (score >= 50 ? "REVIEW" : "APPROVED");
        if (!observed.equals(expected)) {
          return new Result(
              false,
              "risk outcome mismatch: expected="
                  + expected
                  + " observed="
                  + observed
                  + " (score="
                  + score
                  + ")");
        }
      }
      case "compliance" -> {
        String outcome = rawVerdict.path("outcome").asText();
        if (!c.expectedOutcome().equals(outcome)) {
          return new Result(
              false,
              "compliance outcome mismatch: expected="
                  + c.expectedOutcome()
                  + " observed="
                  + outcome);
        }
        if (c.expectedCitationId() != null) {
          boolean found = false;
          for (JsonNode cit : rawVerdict.path("citations")) {
            if (c.expectedCitationId().equals(cit.asText())) {
              found = true;
              break;
            }
          }
          if (!found) {
            return new Result(
                false, "compliance citation missing: expected " + c.expectedCitationId());
          }
        }
      }
      case "routing" -> {
        String pspId = rawVerdict.path("pspId").asText();
        if (c.expectedPspId() != null && !c.expectedPspId().equals(pspId)) {
          return new Result(
              false,
              "routing pspId mismatch: expected=" + c.expectedPspId() + " observed=" + pspId);
        }
      }
      default -> {
        return new Result(false, "unknown agent type: " + c.agent());
      }
    }

    return new Result(true, "ok");
  }

  public record Result(boolean pass, String note) {}
}
