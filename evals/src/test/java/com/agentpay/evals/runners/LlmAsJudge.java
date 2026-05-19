package com.agentpay.evals.runners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * Grades a rationale 0–5 via Claude Haiku 4.5 against the rubric in {@code prompts/judge.md}
 * (FR-E-003.2). The judge.md prompt lives under {@code evals/src/main/resources/prompts/judge.md}
 * (introduced in Iter 4a alongside the rubric); this runner loads it via the classpath.
 */
public final class LlmAsJudge {

  private static final String JUDGE_MODEL = "claude-haiku-4-5";

  private final ChatClient chatClient;
  private final String systemPrompt;
  private final ObjectMapper objectMapper;

  public LlmAsJudge(ChatClient.Builder builder, ObjectMapper objectMapper) {
    this.systemPrompt = loadJudgePrompt();
    this.chatClient =
        builder
            .defaultOptions(
                AnthropicChatOptions.builder().model(JUDGE_MODEL).temperature(0.0).build())
            .build();
    this.objectMapper = objectMapper;
  }

  public JudgeGrade grade(
      GoldenCase c, JsonNode rawVerdict, List<Map<String, Object>> toolEvidence) {
    String user = renderUserMessage(c, rawVerdict, toolEvidence);
    return chatClient.prompt().system(systemPrompt).user(user).call().entity(JudgeGrade.class);
  }

  private String renderUserMessage(
      GoldenCase c, JsonNode rawVerdict, List<Map<String, Object>> toolEvidence) {
    StringBuilder sb = new StringBuilder(512);
    sb.append("Case under review:\n```\n");
    sb.append("caseId=").append(c.caseId()).append('\n');
    sb.append("agentName=").append(agentDisplayName(c.agent())).append('\n');
    sb.append("model=").append(agentModel(c.agent())).append('\n');
    sb.append("expectedOutcome=").append(c.expectedOutcome()).append('\n');
    sb.append("expectedReasonClass=").append(c.expectedReasonClass()).append('\n');
    sb.append("```\n\n");

    sb.append("Agent verdict:\n```json\n");
    try {
      sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rawVerdict));
    } catch (IOException e) {
      throw new UncheckedIOException("failed to render verdict for judge prompt", e);
    }
    sb.append("\n```\n\n");

    sb.append("Tool call evidence:\n```\n");
    if (toolEvidence == null || toolEvidence.isEmpty()) {
      sb.append("(none)\n");
    } else {
      for (int i = 0; i < toolEvidence.size(); i++) {
        Map<String, Object> e = toolEvidence.get(i);
        sb.append(i + 1).append(". tool=").append(e.get("tool"));
        sb.append(" | args=").append(e.get("args"));
        sb.append(" | result=").append(e.get("result")).append('\n');
      }
    }
    sb.append("```\n");
    return sb.toString();
  }

  private static String agentDisplayName(String agent) {
    return switch (agent) {
      case "risk" -> "RiskAgent";
      case "compliance" -> "ComplianceAgent";
      case "routing" -> "RoutingAgent";
      default -> agent;
    };
  }

  private static String agentModel(String agent) {
    return switch (agent) {
      case "risk", "compliance" -> "claude-sonnet-4-6";
      case "routing" -> JUDGE_MODEL;
      default -> "unknown";
    };
  }

  private static String loadJudgePrompt() {
    try {
      return StreamUtils.copyToString(
          new ClassPathResource("prompts/judge.md").getInputStream(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to load prompts/judge.md", e);
    }
  }
}
