package com.agentpay.orchestrator.agents;

import com.agentpay.orchestrator.agents.support.AgentVerdictRecorder;
import com.agentpay.orchestrator.agents.support.StructuredOutputRetry;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RouteRecommendation;
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
 * RoutingAgent — FR-A-RT-001..003. Selects one of three mock-PSP routes via Claude Haiku 4.5.
 *
 * <p>TODO (Iter 4b.2): replace the hardcoded RAG block below with a real pgvector top-K=3 retrieval
 * driven by an embedding of the payment context. For 4b.1 the stub block matches the three
 * candidates the prompt examples were written against so the agent's selection logic can be
 * exercised in unit tests without RAG wiring.
 */
@Service
public class RoutingAgent {

  static final String AGENT_NAME = "routing";

  /**
   * Iter 4b.1 stub: three fixed candidates whose numbers come from FR-P-002 (psp-a 95%/30bps, psp-b
   * 88%/20bps, psp-c 98%/45bps). Replaced by real pgvector retrieval in 4b.2.
   */
  private static final String STUB_RAG_BLOCK =
      """
      pspId=psp-a
      routeId=route-us-1
      expectedSuccessRate=0.952
      expectedCostBps=30
      sampleSize=1240
      observedAt=2026-05-10T00:00:00Z
      notes=domestic USD; stable

      pspId=psp-b
      routeId=route-us-1
      expectedSuccessRate=0.881
      expectedCostBps=20
      sampleSize=980
      observedAt=2026-05-10T00:00:00Z
      notes=domestic USD; cheaper but lower success

      pspId=psp-c
      routeId=route-us-1
      expectedSuccessRate=0.978
      expectedCostBps=45
      sampleSize=520
      observedAt=2026-05-10T00:00:00Z
      notes=domestic USD; highest success, premium cost
      """;

  private final ChatClient chatClient;
  private final String systemPrompt;
  private final String model;
  private final AgentVerdictRecorder recorder;
  private final Clock clock;
  private final BeanOutputConverter<RouteRecommendation> converter =
      new BeanOutputConverter<>(RouteRecommendation.class);

  public RoutingAgent(
      ChatClient.Builder chatClientBuilder,
      @Value("classpath:prompts/routing.md") Resource promptResource,
      @Value("${agentpay.models.routing}") String model,
      AgentVerdictRecorder recorder,
      Clock clock) {
    this.model = model;
    this.chatClient =
        chatClientBuilder
            .defaultOptions(AnthropicChatOptions.builder().model(model).temperature(0.0).build())
            .build();
    this.systemPrompt = loadPrompt(promptResource);
    this.recorder = recorder;
    this.clock = clock;
  }

  public RouteRecommendation route(PaymentContext ctx) {
    String user = PaymentContextRenderer.render(ctx) + "\n" + STUB_RAG_BLOCK;
    String system = systemPrompt + "\n\n" + converter.getFormat();
    Instant start = clock.instant();

    return StructuredOutputRetry.withRetry(
        AGENT_NAME,
        () -> {
          ChatResponse response =
              chatClient.prompt().system(system).user(user).call().chatResponse();
          String text = response.getResult().getOutput().getText();
          RouteRecommendation verdict = converter.convert(text);
          Duration latency = Duration.between(start, clock.instant());
          recorder.record(ctx.caseId(), AGENT_NAME, model, verdict, response, latency);
          return verdict;
        },
        () -> {
          // No review-fallback record type for routing — routing failure is handled upstream by
          // the Supervisor returning Decision with route=Optional.empty(). For Iter 4b.1 we still
          // surface a deterministic safe pick (psp-c, highest success rate) so tests can
          // exercise the both-attempts-fail path; the Supervisor in 4b.2 will treat any agent-
          // level failure path as REVIEW per FR-DP-002 regardless of what we return here.
          RouteRecommendation fallback =
              new RouteRecommendation(
                  "psp-c", "route-us-1", 0.978f, 45, "RoutingAgent retry exhausted; safe pick");
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
