package com.agentpay.orchestrator.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentpay.orchestrator.agents.support.AgentVerdictRecorder;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RiskAssessment;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

class RiskAgentTest {

  private static final String MODEL = "claude-sonnet-4-6";
  private static final String VALID_JSON =
      "{\"score\":65,\"signals\":[\"velocity:24h=27\"],\"rationale\":\"high velocity\"}";

  private ChatClient chatClient;
  private ChatClient.Builder builder;
  private AgentVerdictRecorder recorder;
  private RiskAgent agent;

  @BeforeEach
  void setUp() {
    chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    builder = mock(ChatClient.Builder.class);
    when(builder.defaultOptions(any())).thenReturn(builder);
    when(builder.build()).thenReturn(chatClient);
    recorder = mock(AgentVerdictRecorder.class);
    Resource promptResource = new ByteArrayResource("test risk prompt".getBytes());
    Clock clock = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
    agent = new RiskAgent(builder, promptResource, MODEL, recorder, clock);
  }

  @Test
  void happyPathReturnsParsedVerdictAndPersists() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(AgentTestSupport.stubResponse(VALID_JSON, 120, 40));

    RiskAssessment r = agent.assess(ctx());

    assertThat(r.score()).isEqualTo(65);
    assertThat(r.signals()).containsExactly("velocity:24h=27");
    verify(recorder)
        .record(eq("case-1"), eq("risk"), eq(MODEL), eq(r), any(ChatResponse.class), any());
  }

  @Test
  void retryOnFirstFailureSucceedsOnSecond() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(new RuntimeException("parse failure"))
        .thenReturn(AgentTestSupport.stubResponse(VALID_JSON, 120, 40));

    RiskAssessment r = agent.assess(ctx());

    assertThat(r.score()).isEqualTo(65);
    // Recorder is called exactly once — on the successful attempt only.
    verify(recorder, times(1)).record(any(), any(), any(), any(), any(), any());
    // The chatResponse() method was called twice — first attempt + retry.
    verify(chatClient.prompt().system(anyString()).user(anyString()).call(), times(2))
        .chatResponse();
  }

  @Test
  void bothAttemptsFailEmitsFallbackAndPersists() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(new RuntimeException("first"))
        .thenThrow(new RuntimeException("second"));

    RiskAssessment r = agent.assess(ctx());

    // Review fallback sentinel: score=50, signal "agent:error".
    assertThat(r.score()).isEqualTo(50);
    assertThat(r.signals()).containsExactly("agent:error");
    assertThat(r.rationale()).contains("retry exhausted");
    // Fallback IS persisted; the rawResponse argument is null on the fallback path.
    verify(recorder).record(eq("case-1"), eq("risk"), eq(MODEL), eq(r), eq(null), any());
    verify(recorder, never()).record(any(), any(), any(), any(), any(ChatResponse.class), any());
  }

  private PaymentContext ctx() {
    return new PaymentContext(
        "case-1",
        "agent-buyer-001",
        "merchant-acme",
        new BigDecimal("42.50"),
        "USD",
        "test",
        Map.of("buyer.country", "US", "merchant.country", "US"));
  }
}
