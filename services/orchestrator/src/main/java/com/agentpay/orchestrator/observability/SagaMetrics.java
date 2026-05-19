package com.agentpay.orchestrator.observability;

import com.agentpay.orchestrator.domain.DecisionOutcome;
import com.agentpay.orchestrator.domain.SagaState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Micrometer meters that back the Grafana panels in {@code ops/grafana/dashboards/agentplane.json}
 * (NFR-O-004). Naming uses dots which Spring Boot's Prometheus exporter normalizes to underscores,
 * so {@code agentpay.case.cost.usd} surfaces in Prometheus as {@code agentpay_case_cost_usd}.
 * Counters and timers are cached per tag combination to avoid the per-call registry lookup cost —
 * registries are thread-safe but caching keeps the happy-path allocation-free.
 */
@Component
public class SagaMetrics {

  private final MeterRegistry registry;
  private final DistributionSummary caseCostUsd;
  private final ConcurrentMap<String, Counter> decisionCounters = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> terminalCounters = new ConcurrentHashMap<>();
  private final Counter compensatedCounter;
  private final ConcurrentMap<String, Timer> specialistTimers = new ConcurrentHashMap<>();

  public SagaMetrics(MeterRegistry registry) {
    this.registry = registry;
    // Cost is a low-frequency, high-precision distribution — publish a percentile histogram so
    // Grafana can render median / p95 / max via histogram_quantile().
    this.caseCostUsd =
        DistributionSummary.builder("agentpay.case.cost.usd")
            .description("Per-case total cost in USD at terminal/suspend (NFR-O-003).")
            .baseUnit("USD")
            .publishPercentileHistogram()
            .register(registry);
    this.compensatedCounter =
        Counter.builder("agentpay.saga.compensated.total")
            .description("Count of cases that reached COMPENSATED (NFR-O-004 compensation rate).")
            .register(registry);
  }

  /**
   * Records the per-case cost on terminal/suspend. {@code cost} may be null on no-verdict cases.
   */
  public void recordCaseCost(BigDecimal cost) {
    if (cost == null) {
      return;
    }
    caseCostUsd.record(cost.doubleValue());
  }

  /** Increments the decision-outcome counter (NFR-O-004 panel: decision rate by outcome). */
  public void countDecision(DecisionOutcome outcome) {
    decisionCounters
        .computeIfAbsent(
            outcome.name(),
            o ->
                Counter.builder("agentpay.decision.total")
                    .description("Count of Decisions aggregated by Supervisor, tagged by outcome.")
                    .tag("outcome", o)
                    .register(registry))
        .increment();
  }

  /**
   * Increments the saga-terminal counter (NFR-O-004 panel: compensation rate denominator). Also
   * bumps the dedicated compensated counter when the terminal state is {@link
   * SagaState#COMPENSATED}.
   */
  public void countTerminal(SagaState terminal) {
    terminalCounters
        .computeIfAbsent(
            terminal.name(),
            s ->
                Counter.builder("agentpay.saga.terminal.total")
                    .description("Count of cases that reached a terminal state, tagged by state.")
                    .tag("state", s)
                    .register(registry))
        .increment();
    if (terminal == SagaState.COMPENSATED) {
      compensatedCounter.increment();
    }
  }

  /**
   * Records per-specialist call latency (NFR-O-004 panel: specialist latency p95). {@code agent} is
   * the {@code AGENT_NAME} constant on each specialist class ({@code risk}, {@code compliance},
   * {@code routing}).
   */
  public void recordSpecialistLatency(String agent, Duration latency) {
    specialistTimers
        .computeIfAbsent(
            agent,
            a ->
                Timer.builder("agentpay.specialist.latency")
                    .description("Per-specialist call latency, tagged by agent name.")
                    .tag("agent", a)
                    .publishPercentileHistogram()
                    .register(registry))
        .record(latency);
  }
}
