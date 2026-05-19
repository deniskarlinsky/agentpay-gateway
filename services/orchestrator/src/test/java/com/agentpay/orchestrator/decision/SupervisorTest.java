package com.agentpay.orchestrator.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentpay.orchestrator.agents.ComplianceAgent;
import com.agentpay.orchestrator.agents.RiskAgent;
import com.agentpay.orchestrator.agents.RoutingAgent;
import com.agentpay.orchestrator.domain.ComplianceVerdict;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.DecisionOutcome;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RiskAssessment;
import com.agentpay.orchestrator.domain.RouteRecommendation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FR-DP-001..005: drives every aggregation branch from FR-DP-002 against mocked agents. */
class SupervisorTest {

  private RiskAgent risk;
  private ComplianceAgent compliance;
  private RoutingAgent routing;
  private Supervisor supervisor;

  @BeforeEach
  void setUp() {
    risk = mock(RiskAgent.class);
    compliance = mock(ComplianceAgent.class);
    routing = mock(RoutingAgent.class);
    supervisor = new Supervisor(risk, compliance, routing);
  }

  @AfterEach
  void tearDown() {
    supervisor.shutdown();
  }

  @Test
  void approvedWhenAllClear() {
    when(risk.assess(any())).thenReturn(new RiskAssessment(10, List.of(), "no signal"));
    when(compliance.check(any())).thenReturn(new ComplianceVerdict(Outcome.PASS, List.of(), "ok"));
    when(routing.route(any()))
        .thenReturn(new RouteRecommendation("psp-c", "route-us-1", 0.978f, 45, "highest success"));

    Decision d = supervisor.decide(ctx());

    assertThat(d.outcome()).isEqualTo(DecisionOutcome.APPROVED);
    assertThat(d.route()).isPresent();
    assertThat(d.route().get().pspId()).isEqualTo("psp-c");
    assertThat(d.riskScore()).isEqualTo(10);
    assertThat(d.rationale()).anyMatch(s -> s.startsWith("risk: "));
    assertThat(d.rationale()).anyMatch(s -> s.startsWith("compliance: "));
    assertThat(d.rationale()).anyMatch(s -> s.startsWith("routing: "));
  }

  @Test
  void declinedWhenComplianceFails() {
    when(risk.assess(any())).thenReturn(new RiskAssessment(10, List.of(), "no signal"));
    when(compliance.check(any()))
        .thenReturn(new ComplianceVerdict(Outcome.FAIL, List.of("SYN-021"), "sanctions match"));
    when(routing.route(any()))
        .thenReturn(new RouteRecommendation("psp-c", "route-us-1", 0.978f, 45, "n/a"));

    Decision d = supervisor.decide(ctx());

    assertThat(d.outcome()).isEqualTo(DecisionOutcome.DECLINED);
    assertThat(d.route()).isEmpty();
    assertThat(d.compliance().citations()).containsExactly("SYN-021");
  }

  @Test
  void declinedWhenRiskAtOrAbove80() {
    when(risk.assess(any())).thenReturn(new RiskAssessment(85, List.of("velocity"), "high risk"));
    when(compliance.check(any())).thenReturn(new ComplianceVerdict(Outcome.PASS, List.of(), "ok"));
    when(routing.route(any()))
        .thenReturn(new RouteRecommendation("psp-c", "route-us-1", 0.978f, 45, "n/a"));

    Decision d = supervisor.decide(ctx());

    assertThat(d.outcome()).isEqualTo(DecisionOutcome.DECLINED);
    assertThat(d.riskScore()).isEqualTo(85);
  }

  @Test
  void reviewWhenRiskIn50To79Band() {
    when(risk.assess(any()))
        .thenReturn(new RiskAssessment(65, List.of("amount-pattern"), "borderline"));
    when(compliance.check(any())).thenReturn(new ComplianceVerdict(Outcome.PASS, List.of(), "ok"));
    when(routing.route(any()))
        .thenReturn(new RouteRecommendation("psp-c", "route-us-1", 0.978f, 45, "n/a"));

    Decision d = supervisor.decide(ctx());

    assertThat(d.outcome()).isEqualTo(DecisionOutcome.REVIEW);
    // Iter 4b.3 (FR-O-005): REVIEW preserves the computed route so a human GRANTED can resume
    // through ROUTED → commit without re-calling the routing agent.
    assertThat(d.route()).isPresent();
    assertThat(d.route().get().pspId()).isEqualTo("psp-c");
  }

  @Test
  void reviewWhenComplianceIsReview() {
    when(risk.assess(any())).thenReturn(new RiskAssessment(10, List.of(), "no signal"));
    when(compliance.check(any()))
        .thenReturn(new ComplianceVerdict(Outcome.REVIEW, List.of(), "ambiguous"));
    when(routing.route(any()))
        .thenReturn(new RouteRecommendation("psp-c", "route-us-1", 0.978f, 45, "n/a"));

    Decision d = supervisor.decide(ctx());

    assertThat(d.outcome()).isEqualTo(DecisionOutcome.REVIEW);
  }

  @Test
  void routingFailureDegradesApprovedToReview() {
    when(risk.assess(any())).thenReturn(new RiskAssessment(10, List.of(), "no signal"));
    when(compliance.check(any())).thenReturn(new ComplianceVerdict(Outcome.PASS, List.of(), "ok"));
    when(routing.route(any())).thenThrow(new RuntimeException("routing exploded"));

    Decision d = supervisor.decide(ctx());

    assertThat(d.outcome()).isEqualTo(DecisionOutcome.REVIEW);
    assertThat(d.route()).isEmpty();
    assertThat(d.rationale()).anyMatch(s -> s.contains("routing: unavailable"));
  }

  @Test
  void riskAgentFailureSurfacedAsReviewSentinelScore50() {
    // RiskAssessment.review() returns score=50 → triggers FR-DP-002 review band.
    when(risk.assess(any())).thenThrow(new RuntimeException("risk exploded"));
    when(compliance.check(any())).thenReturn(new ComplianceVerdict(Outcome.PASS, List.of(), "ok"));
    when(routing.route(any()))
        .thenReturn(new RouteRecommendation("psp-c", "route-us-1", 0.978f, 45, "n/a"));

    Decision d = supervisor.decide(ctx());

    assertThat(d.outcome()).isEqualTo(DecisionOutcome.REVIEW);
    assertThat(d.riskScore()).isEqualTo(50);
  }

  private static PaymentContext ctx() {
    return new PaymentContext(
        "case-test",
        "agent-1",
        "merchant-1",
        new BigDecimal("42.50"),
        "USD",
        "test",
        Map.of("buyer.country", "US", "merchant.country", "US"));
  }
}
