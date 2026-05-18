package com.agentpay.orchestrator.agents.support;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;

/**
 * NFR-Q-002: structured-output validation failures retry once; second failure → {@code REVIEW}.
 *
 * <p>Retry contract: {@code call} is invoked once. If it throws a {@link RuntimeException} that
 * isn't a {@link NonTransientAiException} (the Spring AI marker for auth / quota / bad-request
 * faults that won't change on retry), {@code call} is invoked a second time. If that also throws,
 * the supplied {@code reviewFallback} is invoked and its result returned. A {@link
 * NonTransientAiException} is rethrown immediately — retrying a 401/429 is wasteful.
 *
 * <p>Both attempts log at WARN with full stack trace on failure to support post-incident triage.
 */
public final class StructuredOutputRetry {

  private static final Logger log = LoggerFactory.getLogger(StructuredOutputRetry.class);

  private StructuredOutputRetry() {}

  public static <T> T withRetry(String agentName, Supplier<T> call, Supplier<T> reviewFallback) {
    try {
      return call.get();
    } catch (NonTransientAiException permanent) {
      log.warn("{}: non-transient AI error — not retrying", agentName, permanent);
      throw permanent;
    } catch (RuntimeException firstFailure) {
      log.warn("{}: first attempt failed; retrying once", agentName, firstFailure);
      try {
        return call.get();
      } catch (NonTransientAiException permanent) {
        log.warn("{}: non-transient AI error on retry — not falling back", agentName, permanent);
        throw permanent;
      } catch (RuntimeException secondFailure) {
        log.warn(
            "{}: retry also failed — emitting REVIEW fallback verdict", agentName, secondFailure);
        return reviewFallback.get();
      }
    }
  }
}
