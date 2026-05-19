package com.agentpay.orchestrator.agents;

import com.agentpay.orchestrator.agents.routing.RouteCandidate;
import com.agentpay.orchestrator.agents.routing.RouteMetricsRetriever;
import com.agentpay.orchestrator.agents.support.AgentVerdictRecorder;
import com.agentpay.orchestrator.agents.support.PiiRedactionAdvisor;
import com.agentpay.orchestrator.agents.support.StructuredOutputRetry;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RouteRecommendation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/** RoutingAgent — FR-A-RT-001..003. Selects one of three mock-PSP routes via Claude Haiku 4.5. */
@Service
public class RoutingAgent {

  static final String AGENT_NAME = "routing";
  private static final int RAG_TOP_K = 3;

  private final ChatClient chatClient;
  private final String systemPrompt;
  private final String model;
  private final AgentVerdictRecorder recorder;
  private final Clock clock;
  private final RouteMetricsRetriever retriever;
  private final BeanOutputConverter<RouteRecommendation> converter =
      new BeanOutputConverter<>(RouteRecommendation.class);

  public RoutingAgent(
      ChatClient.Builder chatClientBuilder,
      @Value("classpath:prompts/routing.md") Resource promptResource,
      @Value("${agentpay.models.routing}") String model,
      AgentVerdictRecorder recorder,
      Clock clock,
      RouteMetricsRetriever retriever,
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
    this.retriever = retriever;
  }

  public RouteRecommendation route(PaymentContext ctx) {
    List<RouteCandidate> candidates = retriever.retrieveTopK(ctx, RAG_TOP_K);
    String ragBlock = retriever.renderAsKeyValueBlock(candidates);
    String user = PaymentContextRenderer.render(ctx) + "\n" + ragBlock;
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
          // surface a deterministic safe pick (psp-c, highest success rate) so tests can exercise
          // the both-attempts-fail path; the Supervisor in 4b.2 treats any agent-level failure
          // path as REVIEW per FR-DP-002 regardless of what we return here.
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
