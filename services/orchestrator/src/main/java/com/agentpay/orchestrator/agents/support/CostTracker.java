package com.agentpay.orchestrator.agents.support;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * In-memory running per-case cost (NFR-COST-001). {@link AgentVerdictRecorder} pumps cost into this
 * bean as each specialist persists its verdict; the {@link
 * com.agentpay.orchestrator.decision.Supervisor} reads it inside each {@code whenComplete} callback
 * to short-circuit the fan-out once the configured per-case budget is breached.
 *
 * <p>The map is bounded by the count of in-flight cases; {@link #clear} runs after the supervisor
 * settles a case so terminal/REVIEW/budget-exceeded cases don't leak entries. {@link BigDecimal}
 * accumulation via the {@code ConcurrentMap.merge} idiom is atomic per key — no lock needed.
 */
@Component
public class CostTracker {

  private final ConcurrentMap<String, BigDecimal> totals = new ConcurrentHashMap<>();

  /**
   * Adds {@code cost} to the running total for {@code caseId} and returns the new running total.
   */
  public BigDecimal add(String caseId, BigDecimal cost) {
    if (cost == null) {
      return totals.getOrDefault(caseId, BigDecimal.ZERO);
    }
    return totals.merge(caseId, cost, BigDecimal::add);
  }

  /** Current running cost for {@code caseId}; {@link BigDecimal#ZERO} if no agent has reported. */
  public BigDecimal totalFor(String caseId) {
    return totals.getOrDefault(caseId, BigDecimal.ZERO);
  }

  /**
   * Removes the running total for {@code caseId}. Idempotent — safe to call after each decide().
   */
  public void clear(String caseId) {
    totals.remove(caseId);
  }
}
