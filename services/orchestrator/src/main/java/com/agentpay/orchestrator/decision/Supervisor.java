package com.agentpay.orchestrator.decision;

import com.agentpay.orchestrator.agents.ComplianceAgent;
import com.agentpay.orchestrator.agents.RiskAgent;
import com.agentpay.orchestrator.agents.RoutingAgent;
import com.agentpay.orchestrator.agents.support.CostTracker;
import com.agentpay.orchestrator.domain.ComplianceVerdict;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RiskAssessment;
import com.agentpay.orchestrator.domain.RouteRecommendation;
import com.agentpay.orchestrator.observability.SagaMetrics;
import io.micrometer.context.ContextSnapshotFactory;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * FR-DP-001..005: fans the {@link PaymentContext} out to the three specialist agents in parallel on
 * virtual threads, applies per-call 10-second timeouts, and aggregates the verdicts per FR-DP-002.
 * The Supervisor makes no external calls of its own (FR-DP-005) — observability is emitted by
 * Spring AI inside the agent ChatClient layer.
 *
 * <p>Virtual-thread context propagation (FR-DP-004, Iter 6): {@code
 * Executors.newVirtualThreadPerTaskExecutor()} does NOT carry Micrometer Observation /
 * OpenTelemetry context across {@code supplyAsync}. We capture a {@link ContextSnapshot} on the
 * calling thread and wrap each task with it so chat-model spans become children of the case
 * observation opened in {@code PaymentSaga.start}.
 *
 * <p>Budget circuit breaker (Iter 6, NFR-COST-001): {@link CostTracker} accumulates per-call cost
 * as each agent's {@link com.agentpay.orchestrator.agents.support.AgentVerdictRecorder} persists
 * its row. Each future's {@code whenComplete} reads the running total; once {@code
 * agentpay.budget.per_case_usd} is breached, the remaining futures are {@code cancel(true)}'d and
 * {@link Decision#budgetReview} is returned. Note: {@code CompletableFuture.cancel(true)} does NOT
 * interrupt the supplyAsync worker — the in-flight Anthropic call may still complete in the
 * background; we just stop waiting for it. That's acceptable for MVP; documenting here so future
 * iterations know where to add real cancellation if needed.
 */
@Service
public class Supervisor {

  private static final Logger log = LoggerFactory.getLogger(Supervisor.class);
  private static final long SPECIALIST_TIMEOUT_SECONDS = 10L;

  private final RiskAgent risk;
  private final ComplianceAgent compliance;
  private final RoutingAgent routing;
  private final CostTracker costTracker;
  private final SagaMetrics sagaMetrics;
  private final BigDecimal perCaseBudgetUsd;
  private final ExecutorService executor;
  private final ContextSnapshotFactory snapshotFactory;

  @org.springframework.beans.factory.annotation.Autowired
  public Supervisor(
      RiskAgent risk,
      ComplianceAgent compliance,
      RoutingAgent routing,
      CostTracker costTracker,
      SagaMetrics sagaMetrics,
      @Value("${agentpay.budget.per_case_usd:0.10}") BigDecimal perCaseBudgetUsd) {
    this.risk = risk;
    this.compliance = compliance;
    this.routing = routing;
    this.costTracker = costTracker;
    this.sagaMetrics = sagaMetrics;
    this.perCaseBudgetUsd = perCaseBudgetUsd;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    this.snapshotFactory = ContextSnapshotFactory.builder().build();
  }

  /**
   * Test-only convenience constructor (public so cross-package E2E tests can subclass): defaults
   * the budget to a value that won't trip in unit tests and binds a no-op metrics registry so the
   * supervisor can run without a Spring context.
   */
  public Supervisor(RiskAgent risk, ComplianceAgent compliance, RoutingAgent routing) {
    this(
        risk,
        compliance,
        routing,
        new CostTracker(),
        new SagaMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
        new BigDecimal("100.00"));
  }

  public Decision decide(PaymentContext ctx) {
    AtomicBoolean budgetExceeded = new AtomicBoolean(false);
    // ContextSnapshot.wrapExecutor returns an Executor that captures the calling-thread context
    // (including the active Micrometer Observation) and installs it inside each task. This is the
    // path supported for Supplier-shaped CompletableFuture.supplyAsync — the alternate
    // snapshot.wrap(Callable) yields a Callable that supplyAsync can't accept directly.
    java.util.concurrent.Executor wrappedExecutor =
        snapshotFactory.captureAll().wrapExecutor(executor);

    CompletableFuture<RiskAssessment> riskFut =
        CompletableFuture.supplyAsync(() -> risk.assess(ctx), wrappedExecutor)
            .orTimeout(SPECIALIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(
                e -> {
                  log.warn("RiskAgent failed or timed out for case {}", ctx.caseId(), e);
                  return RiskAssessment.review("agent-error: " + rootMessage(e));
                });

    CompletableFuture<ComplianceVerdict> complianceFut =
        CompletableFuture.supplyAsync(() -> compliance.check(ctx), wrappedExecutor)
            .orTimeout(SPECIALIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(
                e -> {
                  log.warn("ComplianceAgent failed or timed out for case {}", ctx.caseId(), e);
                  return ComplianceVerdict.review("agent-error: " + rootMessage(e));
                });

    CompletableFuture<RouteRecommendation> routingFut =
        CompletableFuture.supplyAsync(() -> routing.route(ctx), wrappedExecutor)
            .orTimeout(SPECIALIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(
                e -> {
                  log.warn("RoutingAgent failed or timed out for case {}", ctx.caseId(), e);
                  return null;
                });

    List<CompletableFuture<?>> all = List.of(riskFut, complianceFut, routingFut);
    for (CompletableFuture<?> f : all) {
      f.whenComplete(
          (ignored, ignoredErr) -> {
            BigDecimal running = costTracker.totalFor(ctx.caseId());
            if (running.compareTo(perCaseBudgetUsd) > 0
                && budgetExceeded.compareAndSet(false, true)) {
              log.info(
                  "budget exceeded for case {}: running=${} > budget=${} — cancelling siblings",
                  ctx.caseId(),
                  running.toPlainString(),
                  perCaseBudgetUsd.toPlainString());
              for (CompletableFuture<?> other : all) {
                if (other != f) {
                  other.cancel(true);
                }
              }
            }
          });
    }

    try {
      CompletableFuture.allOf(riskFut, complianceFut, routingFut).exceptionally(e -> null).join();

      if (budgetExceeded.get()) {
        BigDecimal running = costTracker.totalFor(ctx.caseId());
        List<String> rationale =
            List.of(
                "budget exceeded: running=$"
                    + running.toPlainString()
                    + " budget=$"
                    + perCaseBudgetUsd.toPlainString());
        // Risk score 50 keeps the Decision record valid and signals "indeterminate" — the saga
        // routes this to SUSPENDED_FOR_REVIEW via the REVIEW outcome regardless of score.
        Decision d =
            Decision.budgetReview(50, ComplianceVerdict.review("budget short-circuit"), rationale);
        sagaMetrics.countDecision(d.outcome());
        return d;
      }

      Decision aggregated =
          aggregate(safeJoin(riskFut), safeJoin(complianceFut), safeJoin(routingFut));
      sagaMetrics.countDecision(aggregated.outcome());
      return aggregated;
    } finally {
      costTracker.clear(ctx.caseId());
    }
  }

  /**
   * FR-DP-002 rule:
   *
   * <ol>
   *   <li>{@code compliance.outcome == FAIL} → DECLINED
   *   <li>else if {@code risk.score >= 80} → DECLINED
   *   <li>else if {@code risk.score >= 50 || compliance.outcome == REVIEW} → REVIEW
   *   <li>else APPROVED (or REVIEW if routing came back null)
   * </ol>
   */
  private Decision aggregate(
      RiskAssessment risk, ComplianceVerdict compliance, RouteRecommendation route) {
    String riskLine = "risk: " + risk.rationale();
    String complianceLine = "compliance: " + compliance.rationale();
    String routingLine = "routing: " + (route != null ? route.rationale() : "unavailable");
    List<String> rationale = List.of(riskLine, complianceLine, routingLine);

    if (compliance.outcome() == Outcome.FAIL) {
      return Decision.declined(risk.score(), compliance, rationale);
    }
    if (risk.score() >= 80) {
      return Decision.declined(risk.score(), compliance, rationale);
    }
    if (risk.score() >= 50 || compliance.outcome() == Outcome.REVIEW) {
      // FR-O-005 (Iter 4b.3): preserve route on REVIEW so onApprovalGranted can resume through
      // ROUTED → commit without re-calling the routing agent. route may be null if RoutingAgent
      // failed; Decision.review then carries Optional.empty().
      return Decision.review(risk.score(), compliance, route, rationale);
    }
    if (route == null) {
      List<String> routingUnavailable = List.of(riskLine, complianceLine, "routing: unavailable");
      return Decision.review(risk.score(), compliance, routingUnavailable);
    }
    return Decision.approved(risk.score(), compliance, route, rationale);
  }

  /**
   * Resolves a future that may have been cancelled by the budget circuit breaker. Cancellation
   * propagates as {@link java.util.concurrent.CancellationException}; we treat the agent as
   * unavailable and return a REVIEW-band sentinel so {@link #aggregate} stays simple.
   */
  @SuppressWarnings("unchecked")
  private static <T> T safeJoin(CompletableFuture<T> future) {
    try {
      return future.getNow(null);
    } catch (java.util.concurrent.CancellationException
        | java.util.concurrent.CompletionException e) {
      return null;
    }
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
  }
}
