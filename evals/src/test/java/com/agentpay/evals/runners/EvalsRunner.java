package com.agentpay.evals.runners;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * FR-E-002..005: runs each golden case through (a) the relevant specialist's prompt against the
 * live Anthropic API, (b) a {@link DeterministicJudge} for outcome / PII / citation / pspId
 * correctness, and (c) an {@link LlmAsJudge} grading the rationale 0–5. Writes {@code
 * evals/results/<timestamp>.json} and asserts the FR-E-005 regression threshold in {@link
 * #threshold()}.
 *
 * <p>Gated by {@code ANTHROPIC_API_KEY} (constraint #5 from the Iter 6 plan): missing the env var
 * produces a clean JUnit skip rather than a cryptic NPE when the ChatClient tries to call out.
 */
@SpringBootTest(classes = EvalsTestApp.class)
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = "sk-ant-.*")
class EvalsRunner {

  private static final double LLM_SCORE_THRESHOLD = 4.0;

  /**
   * Promot resources copied verbatim from {@code services/orchestrator/src/main/resources/prompts}.
   */
  private static final Map<String, String> AGENT_MODEL =
      Map.of(
          "risk",
          "claude-sonnet-4-6",
          "compliance",
          "claude-sonnet-4-6",
          "routing",
          "claude-haiku-4-5");

  @Autowired private ChatClient.Builder chatClientBuilder;

  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private static final List<EvalResult> RESULTS = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> allGoldenCases() throws IOException {
    LlmAsJudge judge = new LlmAsJudge(chatClientBuilder, MAPPER);
    List<GoldenCase> cases = loadGoldenCases();
    return cases.stream().map(c -> dynamicTest(c.caseId(), () -> evaluate(c, judge)));
  }

  private void evaluate(GoldenCase c, LlmAsJudge judge) {
    String systemPrompt = loadAgentPrompt(c.agent());
    String userMessage = renderUserMessage(c);
    JsonNode rawVerdict =
        chatClientBuilder
            .defaultOptions(
                AnthropicChatOptions.builder()
                    .model(AGENT_MODEL.get(c.agent()))
                    .temperature(0.0)
                    .build())
            .build()
            .prompt()
            .system(systemPrompt + "\n\nReturn ONLY a JSON object matching the response schema.")
            .user(userMessage)
            .call()
            .entity(JsonNode.class);

    DeterministicJudge.Result det = DeterministicJudge.judge(c, rawVerdict, userMessage);
    JudgeGrade grade = judge.grade(c, rawVerdict, c.mockToolEvidence());
    RESULTS.add(new EvalResult(c.caseId(), c.agent(), det.pass(), det.note(), grade, rawVerdict));

    // We do NOT fail the dynamic test here on a deterministic miss — the threshold assertion in
    // @AfterAll handles aggregate enforcement (FR-E-005). Individual failures still surface in
    // the result JSON for review.
  }

  /**
   * Renders the PaymentContext using the same {@code key=value} shape the orchestrator's
   * PaymentContextRenderer uses, plus any case-type-specific blocks (tool evidence for Compliance,
   * RAG candidates for Routing). Inlined here to avoid pulling the orchestrator's internal
   * package-private renderer into the eval module's API surface.
   */
  private static String renderUserMessage(GoldenCase c) {
    StringBuilder sb = new StringBuilder(512);
    GoldenCase.PaymentContext pc = c.paymentContext();
    // PII redaction mirroring PiiRedactionAdvisor — for any metadata key the case flags as
    // PII-sensitive, the raw value is replaced before it lands on the user message. This makes the
    // deterministic PII assertion (raw value not in rendered prompt) hold by construction.
    java.util.Set<String> redactKeys =
        c.piiKeysToCheckRedacted() == null
            ? java.util.Set.of()
            : new java.util.HashSet<>(c.piiKeysToCheckRedacted());
    sb.append("caseId=").append(pc.caseId()).append('\n');
    sb.append("agentId=").append(pc.agentId()).append('\n');
    sb.append("merchantId=").append(pc.merchantId()).append('\n');
    sb.append("amount=").append(pc.amount()).append('\n');
    sb.append("currency=").append(pc.currency()).append('\n');
    sb.append("description=").append(pc.description() == null ? "" : pc.description()).append('\n');
    if (pc.agentMetadata() != null) {
      pc.agentMetadata()
          .forEach(
              (k, v) -> {
                String value = redactKeys.contains(k) ? "[REDACTED-PII]" : (v == null ? "" : v);
                sb.append(k).append('=').append(value).append('\n');
              });
    }
    if (c.mockToolEvidence() != null && !c.mockToolEvidence().isEmpty()) {
      sb.append("\nTool call evidence:\n");
      for (int i = 0; i < c.mockToolEvidence().size(); i++) {
        Map<String, Object> e = c.mockToolEvidence().get(i);
        sb.append(i + 1).append(". tool=").append(e.get("tool"));
        sb.append(" | args=").append(e.get("args"));
        sb.append(" | result=").append(e.get("result")).append('\n');
      }
    }
    if ("routing".equals(c.agent()) && c.mockRagCandidates() != null) {
      sb.append("\nRAG candidates (top ").append(c.mockRagCandidates().size()).append("):\n");
      for (Map<String, Object> r : c.mockRagCandidates()) {
        sb.append("- ").append(r).append('\n');
      }
    }
    return sb.toString();
  }

  private static List<GoldenCase> loadGoldenCases() throws IOException {
    Path path = Paths.get("..", "evals", "golden_cases.json").toAbsolutePath().normalize();
    if (!Files.exists(path)) {
      // Run from repo root rather than evals/ — Gradle does this; the relative path above resolves
      // to <repo>/evals/golden_cases.json which doesn't exist. Try the project-local form.
      path = Paths.get("golden_cases.json").toAbsolutePath().normalize();
    }
    return MAPPER.readValue(
        path.toFile(),
        MAPPER.getTypeFactory().constructCollectionType(List.class, GoldenCase.class));
  }

  private static String loadAgentPrompt(String agent) {
    String resourcePath = "prompts/" + agent + ".md";
    try (var in = EvalsRunner.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException(
            "could not locate "
                + resourcePath
                + " on classpath — does the orchestrator module export it?");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to load " + resourcePath, e);
    }
  }

  @AfterAll
  static void threshold() throws IOException {
    if (RESULTS.isEmpty()) {
      // Either no API key (the @EnabledIfEnvironmentVariable should have skipped) or the
      // @TestFactory failed to produce any cases. Treat as a hard miss so CI catches the
      // regression.
      throw new AssertionError("no eval results produced — did the runner skip every case?");
    }

    double meanScore = RESULTS.stream().mapToInt(r -> r.llmGrade().score()).average().orElse(0.0);
    long detPasses = RESULTS.stream().filter(EvalResult::deterministicPass).count();
    double detPassRate = (double) detPasses / RESULTS.size();

    writeResultsJson(meanScore, detPassRate);

    System.out.println("=== EVAL SUMMARY ===");
    System.out.println("cases:               " + RESULTS.size());
    System.out.println("deterministic_pass:  " + detPasses + " / " + RESULTS.size());
    System.out.println("mean_llm_judge:      " + String.format("%.2f", meanScore));
    System.out.println("=== END EVAL SUMMARY ===");

    // FR-E-005: regression threshold blocks CI on miss.
    if (detPassRate < 1.0) {
      throw new AssertionError(
          "deterministic_pass_rate < 1.0 (" + detPasses + "/" + RESULTS.size() + ")");
    }
    if (meanScore < LLM_SCORE_THRESHOLD) {
      throw new AssertionError(
          "mean_llm_judge_score < " + LLM_SCORE_THRESHOLD + " (was " + meanScore + ")");
    }
  }

  private static void writeResultsJson(double meanScore, double detPassRate) throws IOException {
    String ts =
        OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    Path dir = resolveResultsDir();
    Files.createDirectories(dir);
    Path file = dir.resolve(ts + ".json");

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("timestamp", ts);
    summary.put("total_cases", RESULTS.size());
    summary.put("deterministic_pass_rate", detPassRate);
    summary.put("mean_llm_judge_score", meanScore);
    summary.put("threshold_llm_score", LLM_SCORE_THRESHOLD);
    summary.put("results", RESULTS);

    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), summary);
    // Maintain a stable "latest" pointer so downstream tooling does not have to guess the suffix.
    Files.copy(file, dir.resolve("latest.json"), StandardCopyOption.REPLACE_EXISTING);
  }

  private static Path resolveResultsDir() {
    Path candidate = Paths.get("..", "evals", "results").toAbsolutePath().normalize();
    if (!Files.exists(candidate.getParent())) {
      candidate = Paths.get("results").toAbsolutePath().normalize();
    }
    return candidate;
  }
}
