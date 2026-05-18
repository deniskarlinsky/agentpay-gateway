package com.agentpay.orchestrator.agents.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;

class StructuredOutputRetryTest {

  @Test
  void successOnFirstTry() {
    AtomicInteger calls = new AtomicInteger();
    String r =
        StructuredOutputRetry.withRetry(
            "agent",
            () -> {
              calls.incrementAndGet();
              return "ok";
            },
            () -> "fallback");
    assertThat(r).isEqualTo("ok");
    assertThat(calls).hasValue(1);
  }

  @Test
  void successOnRetry() {
    AtomicInteger calls = new AtomicInteger();
    String r =
        StructuredOutputRetry.withRetry(
            "agent",
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("parse failed");
              }
              return "ok";
            },
            () -> "fallback");
    assertThat(r).isEqualTo("ok");
    assertThat(calls).hasValue(2);
  }

  @Test
  void bothFailuresEmitFallback() {
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger fallbackCalls = new AtomicInteger();
    String r =
        StructuredOutputRetry.withRetry(
            "agent",
            () -> {
              calls.incrementAndGet();
              throw new RuntimeException("parse failed");
            },
            () -> {
              fallbackCalls.incrementAndGet();
              return "fallback";
            });
    assertThat(r).isEqualTo("fallback");
    assertThat(calls).hasValue(2);
    assertThat(fallbackCalls).hasValue(1);
  }

  @Test
  void nonTransientErrorIsRethrownWithoutRetry() {
    AtomicInteger calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                StructuredOutputRetry.withRetry(
                    "agent",
                    () -> {
                      calls.incrementAndGet();
                      throw new NonTransientAiException("401 unauthorized");
                    },
                    () -> "fallback"))
        .isInstanceOf(NonTransientAiException.class);
    assertThat(calls).hasValue(1);
  }

  @Test
  void nonTransientErrorOnRetryIsRethrown() {
    AtomicInteger calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                StructuredOutputRetry.withRetry(
                    "agent",
                    () -> {
                      if (calls.incrementAndGet() == 1) {
                        throw new RuntimeException("flaky parse");
                      }
                      throw new NonTransientAiException("429 throttled");
                    },
                    () -> "fallback"))
        .isInstanceOf(NonTransientAiException.class);
    assertThat(calls).hasValue(2);
  }
}
