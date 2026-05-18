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
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RouteRecommendation;
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

class RoutingAgentTest {

  private static final String MODEL = "claude-haiku-4-5";
  private static final String VALID_JSON =
      "{\"pspId\":\"psp-c\",\"routeId\":\"route-us-1\",\"expectedSuccessRate\":0.978,"
          + "\"expectedCostBps\":45,\"rationale\":\"highest success rate\"}";

  private ChatClient chatClient;
  private ChatClient.Builder builder;
  private AgentVerdictRecorder recorder;
  private RoutingAgent agent;

  @BeforeEach
  void setUp() {
    chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    builder = mock(ChatClient.Builder.class);
    when(builder.defaultOptions(any())).thenReturn(builder);
    when(builder.build()).thenReturn(chatClient);
    recorder = mock(AgentVerdictRecorder.class);
    Resource promptResource = new ByteArrayResource("test routing prompt".getBytes());
    Clock clock = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
    agent = new RoutingAgent(builder, promptResource, MODEL, recorder, clock);
  }

  @Test
  void happyPathReturnsRouteAndPersists() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(AgentTestSupport.stubResponse(VALID_JSON, 350, 60));

    RouteRecommendation r = agent.route(ctx());

    assertThat(r.pspId()).isEqualTo("psp-c");
    assertThat(r.routeId()).isEqualTo("route-us-1");
    assertThat(r.expectedSuccessRate()).isEqualTo(0.978f);
    verify(recorder)
        .record(eq("case-1"), eq("routing"), eq(MODEL), eq(r), any(ChatResponse.class), any());
  }

  @Test
  void retryOnFirstFailureSucceeds() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(new RuntimeException("transient"))
        .thenReturn(AgentTestSupport.stubResponse(VALID_JSON, 350, 60));

    RouteRecommendation r = agent.route(ctx());

    assertThat(r.pspId()).isEqualTo("psp-c");
    verify(recorder, times(1)).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void bothAttemptsFailEmitsSafePickFallback() {
    when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(new RuntimeException("first"))
        .thenThrow(new RuntimeException("second"));

    RouteRecommendation r = agent.route(ctx());

    // Safe pick — highest success rate of the three mock PSPs (FR-P-002).
    assertThat(r.pspId()).isEqualTo("psp-c");
    assertThat(r.rationale()).contains("retry exhausted");
    verify(recorder).record(eq("case-1"), eq("routing"), eq(MODEL), eq(r), eq(null), any());
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
