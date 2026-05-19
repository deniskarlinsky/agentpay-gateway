package com.agentpay.orchestrator.agents.support;

import com.agentpay.orchestrator.observability.SagaMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one row to {@code agent_verdicts} per specialist call (REQUIREMENTS.md §8). Pulls token
 * usage from the {@link ChatResponse} metadata (Spring AI 1.1 exposes it natively) and computes
 * cost via {@link ModelRates}.
 */
@Service
public class AgentVerdictRecorder {

  private static final Logger log = LoggerFactory.getLogger(AgentVerdictRecorder.class);

  private final AgentVerdictRepository repo;
  private final ObjectMapper objectMapper;
  private final ModelRates rates;
  private final Clock clock;
  private final CostTracker costTracker;
  private final SagaMetrics sagaMetrics;

  public AgentVerdictRecorder(
      AgentVerdictRepository repo,
      ObjectMapper objectMapper,
      ModelRates rates,
      Clock clock,
      CostTracker costTracker,
      SagaMetrics sagaMetrics) {
    this.repo = repo;
    this.objectMapper = objectMapper;
    this.rates = rates;
    this.clock = clock;
    this.costTracker = costTracker;
    this.sagaMetrics = sagaMetrics;
  }

  @Transactional("transactionManager")
  public void record(
      String caseId,
      String agentName,
      String model,
      Object verdict,
      ChatResponse rawResponse,
      Duration latency) {
    String json;
    try {
      json = objectMapper.writeValueAsString(verdict);
    } catch (JsonProcessingException e) {
      // Records always serialize. If this throws we have an Object that isn't a JSON-friendly bean
      // — surface loudly so the caller knows persistence failed for a non-network reason.
      throw new IllegalStateException("failed to serialize verdict for case=" + caseId, e);
    }

    int inputTokens = 0;
    int outputTokens = 0;
    if (rawResponse != null && rawResponse.getMetadata() != null) {
      Usage usage = rawResponse.getMetadata().getUsage();
      if (usage != null) {
        // Spring AI 1.1 exposes prompt/completion-token counts via Usage. Boxed types may be null
        // on some providers — guard with intValue() after a null check.
        Integer in = usage.getPromptTokens();
        Integer out = usage.getCompletionTokens();
        inputTokens = in == null ? 0 : in;
        outputTokens = out == null ? 0 : out;
      }
    }

    BigDecimal cost;
    try {
      cost = rates.calculate(model, inputTokens, outputTokens);
    } catch (IllegalArgumentException e) {
      // Don't fail the case because a rate is missing — record zero-cost and warn so ops sees it.
      log.warn(
          "missing rate config for model='{}' (case={}, agent={}) — recording cost=0",
          model,
          caseId,
          agentName);
      cost = BigDecimal.ZERO.setScale(6);
    }

    AgentVerdict entity =
        new AgentVerdict(
            caseId,
            agentName,
            model,
            json,
            inputTokens,
            outputTokens,
            cost,
            (int) latency.toMillis(),
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    repo.save(entity);
    // NFR-COST-001: pump per-call cost into the running per-case total so the Supervisor's
    // whenComplete tally sees it as soon as the agent's CompletableFuture settles.
    costTracker.add(caseId, cost);
    // NFR-O-004 (specialist latency p95 panel).
    sagaMetrics.recordSpecialistLatency(agentName, latency);
  }
}
