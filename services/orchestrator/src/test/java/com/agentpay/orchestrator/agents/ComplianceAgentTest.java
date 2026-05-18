package com.agentpay.orchestrator.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentpay.orchestrator.agents.support.AgentVerdictRecorder;
import com.agentpay.orchestrator.domain.ComplianceVerdict;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

class ComplianceAgentTest {

  private static final String MODEL = "claude-sonnet-4-6";
  private static final String PASS_JSON =
      "{\"outcome\":\"PASS\",\"citations\":[],\"rationale\":\"both clear\"}";
  private static final String FAIL_JSON =
      "{\"outcome\":\"FAIL\",\"citations\":[\"SYN-021\",\"list:SYNTHETIC-SDN\"],\"rationale\":\"buyer matched\"}";

  private ChatClient chatClient;
  private ChatClient.Builder builder;
  private AgentVerdictRecorder recorder;
  private ComplianceAgent agent;

  @BeforeEach
  void setUp() {
    chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    builder = mock(ChatClient.Builder.class);
    when(builder.defaultOptions(any())).thenReturn(builder);
    when(builder.defaultToolCallbacks(any(ToolCallback[].class))).thenReturn(builder);
    when(builder.build()).thenReturn(chatClient);
    ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
    when(provider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
    recorder = mock(AgentVerdictRecorder.class);
    Resource promptResource = new ByteArrayResource("test compliance prompt".getBytes());
    Clock clock = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
    agent = new ComplianceAgent(builder, provider, promptResource, MODEL, recorder, clock);
  }

  @Test
  void passVerdictParsedAndPersisted() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(AgentTestSupport.stubResponse(PASS_JSON, 200, 30));

    ComplianceVerdict v = agent.check(ctx());

    assertThat(v.outcome()).isEqualTo(Outcome.PASS);
    assertThat(v.citations()).isEmpty();
    verify(recorder)
        .record(eq("case-1"), eq("compliance"), eq(MODEL), eq(v), any(ChatResponse.class), any());
  }

  @Test
  void failVerdictParsedAndPersisted() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(AgentTestSupport.stubResponse(FAIL_JSON, 220, 35));

    ComplianceVerdict v = agent.check(ctx());

    assertThat(v.outcome()).isEqualTo(Outcome.FAIL);
    assertThat(v.citations()).containsExactly("SYN-021", "list:SYNTHETIC-SDN");
    verify(recorder)
        .record(eq("case-1"), eq("compliance"), eq(MODEL), eq(v), any(ChatResponse.class), any());
  }

  @Test
  void retryOnFirstFailureSucceeds() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(new RuntimeException("transient"))
        .thenReturn(AgentTestSupport.stubResponse(PASS_JSON, 200, 30));

    ComplianceVerdict v = agent.check(ctx());

    assertThat(v.outcome()).isEqualTo(Outcome.PASS);
    verify(recorder, times(1)).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void bothAttemptsFailEmitsReviewFallback() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(new RuntimeException("first"))
        .thenThrow(new RuntimeException("second"));

    ComplianceVerdict v = agent.check(ctx());

    assertThat(v.outcome()).isEqualTo(Outcome.REVIEW);
    assertThat(v.citations()).isEmpty();
    assertThat(v.rationale()).contains("retry exhausted");
    verify(recorder).record(eq("case-1"), eq("compliance"), eq(MODEL), eq(v), eq(null), any());
  }

  private PaymentContext ctx() {
    return new PaymentContext(
        "case-1",
        "agent-buyer-001",
        "merchant-acme",
        new BigDecimal("42.50"),
        "USD",
        "test",
        Map.of(
            "buyer.name", "Alice Buyer",
            "buyer.country", "US",
            "merchant.name", "Acme Widgets Ltd",
            "merchant.country", "US"));
  }
}
