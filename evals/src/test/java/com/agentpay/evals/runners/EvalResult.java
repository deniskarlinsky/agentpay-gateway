package com.agentpay.evals.runners;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One row in {@code evals/results/<timestamp>.json} (FR-E-004). Carries the original case identity,
 * the parsed verdict the agent emitted, both judges' assessments, and any free-form notes the
 * deterministic judge attached.
 */
public record EvalResult(
    String caseId,
    String agent,
    boolean deterministicPass,
    String deterministicNote,
    JudgeGrade llmGrade,
    JsonNode rawVerdict) {}
