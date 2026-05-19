package com.agentpay.evals.runners;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Score + critique returned by the LLM-as-judge (FR-E-003.2). Bound via Spring AI's {@code
 * .entity(JudgeGrade.class)} on the Haiku ChatClient call.
 */
public record JudgeGrade(int score, String critique) {

  @JsonCreator
  public JudgeGrade(@JsonProperty("score") int score, @JsonProperty("critique") String critique) {
    if (score < 0 || score > 5) {
      throw new IllegalArgumentException("score must be in [0, 5], got " + score);
    }
    if (critique == null || critique.isBlank()) {
      throw new IllegalArgumentException("critique must be non-blank");
    }
    this.score = score;
    this.critique = critique;
  }
}
