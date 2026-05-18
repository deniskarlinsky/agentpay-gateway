package com.agentpay.orchestrator.agents;

import com.agentpay.orchestrator.agents.support.AgentVerdictRecorder;
import com.agentpay.orchestrator.agents.support.StructuredOutputRetry;
import com.agentpay.orchestrator.domain.ComplianceVerdict;
import com.agentpay.orchestrator.domain.PaymentContext;
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
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * ComplianceAgent — FR-A-C-001..004. Looks up buyer and merchant via the sanctions MCP server and
 * emits PASS or FAIL.
 *
 * <p>The MCP tool callbacks come from Spring AI's {@code spring-ai-starter-mcp-client}
 * autoconfiguration: the configured streamable-http connections produce a {@link
 * ToolCallbackProvider} bean whose callbacks are attached at builder time so every prompt sent by
 * this agent advertises the MCP tools to the model.
 */
@Service
public class ComplianceAgent {

  static final String AGENT_NAME = "compliance";

  private final ChatClient chatClient;
  private final String systemPrompt;
  private final String model;
  private final AgentVerdictRecorder recorder;
  private final Clock clock;
  private final BeanOutputConverter<ComplianceVerdict> converter =
      new BeanOutputConverter<>(ComplianceVerdict.class);

  public ComplianceAgent(
      ChatClient.Builder chatClientBuilder,
      ToolCallbackProvider mcpToolCallbackProvider,
      @Value("classpath:prompts/compliance.md") Resource promptResource,
      @Value("${agentpay.models.compliance}") String model,
      AgentVerdictRecorder recorder,
      Clock clock) {
    this.model = model;
    this.chatClient =
        chatClientBuilder
            .defaultOptions(AnthropicChatOptions.builder().model(model).temperature(0.0).build())
            .defaultToolCallbacks(mcpToolCallbackProvider.getToolCallbacks())
            .build();
    this.systemPrompt = loadPrompt(promptResource);
    this.recorder = recorder;
    this.clock = clock;
  }

  public ComplianceVerdict check(PaymentContext ctx) {
    String user = PaymentContextRenderer.render(ctx);
    String system = systemPrompt + "\n\n" + converter.getFormat();
    Instant start = clock.instant();

    return StructuredOutputRetry.withRetry(
        AGENT_NAME,
        () -> {
          ChatResponse response =
              chatClient.prompt().system(system).user(user).call().chatResponse();
          String text = response.getResult().getOutput().getText();
          ComplianceVerdict verdict = converter.convert(text);
          Duration latency = Duration.between(start, clock.instant());
          recorder.record(ctx.caseId(), AGENT_NAME, model, verdict, response, latency);
          return verdict;
        },
        () -> {
          ComplianceVerdict fallback = ComplianceVerdict.review("ComplianceAgent retry exhausted");
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
