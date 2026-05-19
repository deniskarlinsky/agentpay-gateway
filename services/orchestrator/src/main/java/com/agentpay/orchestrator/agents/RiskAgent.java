package com.agentpay.orchestrator.agents;

import com.agentpay.orchestrator.agents.support.AgentVerdictRecorder;
import com.agentpay.orchestrator.agents.support.PiiRedactionAdvisor;
import com.agentpay.orchestrator.agents.support.StructuredOutputRetry;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RiskAssessment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * RiskAgent — FR-A-R-001..004. Scores fraud risk on the 0..100 scale via Claude Sonnet 4.6.
 *
 * <p>TODO (later iteration): FR-A-R-002 mandates two read-only tools — {@code velocity_check} and
 * {@code fraud_rules_lookup}. Their implementations are NOT in Iter 4b.1 scope; the agent runs
 * tool-less here, so the prompt's tool-call examples are effectively rationalization-by-the-model
 * rather than real tool invocations. Unit tests bypass this entirely via a mocked {@link
 * ChatClient}; the agent is not exercised against the live LLM in 4b.1.
 */
@Service
public class RiskAgent {

  static final String AGENT_NAME = "risk";

  private final ChatClient chatClient;
  private final String systemPrompt;
  private final String model;
  private final AgentVerdictRecorder recorder;
  private final Clock clock;
  private final BeanOutputConverter<RiskAssessment> converter =
      new BeanOutputConverter<>(RiskAssessment.class);

  public RiskAgent(
      ChatClient.Builder chatClientBuilder,
      @Value("classpath:prompts/risk.md") Resource promptResource,
      @Value("${agentpay.models.risk}") String model,
      AgentVerdictRecorder recorder,
      Clock clock,
      PiiRedactionAdvisor piiRedactionAdvisor) {
    this.model = model;
    this.chatClient =
        chatClientBuilder
            .defaultOptions(AnthropicChatOptions.builder().model(model).temperature(0.0).build())
            .defaultAdvisors(piiRedactionAdvisor)
            .build();
    this.systemPrompt = loadPrompt(promptResource);
    this.recorder = recorder;
    this.clock = clock;
  }

  public RiskAssessment assess(PaymentContext ctx) {
    String user = PaymentContextRenderer.render(ctx);
    String system = systemPrompt + "\n\n" + converter.getFormat();
    Instant start = clock.instant();

    return StructuredOutputRetry.withRetry(
        AGENT_NAME,
        () -> {
          ChatResponse response =
              chatClient.prompt().system(system).user(user).call().chatResponse();
          String text = response.getResult().getOutput().getText();
          RiskAssessment verdict = converter.convert(text);
          Duration latency = Duration.between(start, clock.instant());
          recorder.record(ctx.caseId(), AGENT_NAME, model, verdict, response, latency);
          return verdict;
        },
        () -> {
          RiskAssessment fallback = RiskAssessment.review("RiskAgent retry exhausted");
          Duration latency = Duration.between(start, clock.instant());
          recorder.record(ctx.caseId(), AGENT_NAME, model, fallback, null, latency);
          return fallback;
        });
  }

  private static String loadPrompt(Resource r) {
    try {
      return StreamUtils.copyToString(r.getInputStream(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to load prompt " + r, e);
    }
  }
}
