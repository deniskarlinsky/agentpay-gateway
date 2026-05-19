package com.agentpay.orchestrator.decision;

import com.agentpay.orchestrator.agents.ComplianceAgent;
import com.agentpay.orchestrator.agents.RiskAgent;
import com.agentpay.orchestrator.agents.RoutingAgent;
import com.agentpay.orchestrator.domain.ComplianceVerdict;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RiskAssessment;
import com.agentpay.orchestrator.domain.RouteRecommendation;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FR-DP-001..005: fans the {@link PaymentContext} out to the three specialist agents in parallel on
 * virtual threads, applies per-call 10-second timeouts, and aggregates the verdicts per FR-DP-002.
 * The Supervisor makes no external calls of its own (FR-DP-005) — observability is emitted by
 * Spring AI inside the agent ChatClient layer.
 */
@Service
public class Supervisor {

  private static final Logger log = LoggerFactory.getLogger(Supervisor.class);
  private static final long SPECIALIST_TIMEOUT_SECONDS = 10L;

  private final RiskAgent risk;
  private final ComplianceAgent compliance;
  private final RoutingAgent routing;
  private final ExecutorService executor;

  public Supervisor(RiskAgent risk, ComplianceAgent compliance, RoutingAgent routing) {
    this.risk = risk;
    this.compliance = compliance;
    this.routing = routing;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  public Decision decide(PaymentContext ctx) {
    CompletableFuture<RiskAssessment> riskFut =
        CompletableFuture.supplyAsync(() -> risk.assess(ctx), executor)
            .orTimeout(SPECIALIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(
                e -> {
                  log.warn("RiskAgent failed or timed out for case {}", ctx.caseId(), e);
                  return RiskAssessment.review("agent-error: " + rootMessage(e));
                });

    CompletableFuture<ComplianceVerdict> complianceFut =
        CompletableFuture.supplyAsync(() -> compliance.check(ctx), executor)
            .orTimeout(SPECIALIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(
                e -> {
                  log.warn("ComplianceAgent failed or timed out for case {}", ctx.caseId(), e);
                  return ComplianceVerdict.review("agent-error: " + rootMessage(e));
                });

    CompletableFuture<RouteRecommendation> routingFut =
        CompletableFuture.supplyAsync(() -> routing.route(ctx), executor)
            .orTimeout(SPECIALIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(
                e -> {
                  log.warn("RoutingAgent failed or timed out for case {}", ctx.caseId(), e);
                  return null;
                });

    CompletableFuture.allOf(riskFut, complianceFut, routingFut).join();
    return aggregate(riskFut.join(), complianceFut.join(), routingFut.join());
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
