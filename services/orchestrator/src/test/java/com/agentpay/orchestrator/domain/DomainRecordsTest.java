package com.agentpay.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DomainRecordsTest {

  @Nested
  class RiskAssessmentTests {

    @Test
    void rejectsScoreBelowZero() {
      assertThatThrownBy(() -> new RiskAssessment(-1, List.of(), "x"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("score");
    }

    @Test
    void rejectsScoreAbove100() {
      assertThatThrownBy(() -> new RiskAssessment(101, List.of(), "x"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("score");
    }

    @Test
    void rejectsNullSignals() {
      assertThatThrownBy(() -> new RiskAssessment(0, null, "x"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("signals");
    }

    @Test
    void rejectsBlankRationale() {
      assertThatThrownBy(() -> new RiskAssessment(0, List.of(), "  "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rationale");
    }

    @Test
    void acceptsBoundaryScores() {
      assertThat(new RiskAssessment(0, List.of(), "ok").score()).isZero();
      assertThat(new RiskAssessment(100, List.of(), "ok").score()).isEqualTo(100);
    }

    @Test
    void reviewFactoryProducesSentinel() {
      RiskAssessment r = RiskAssessment.review("agent timed out");
      assertThat(r.score()).isEqualTo(50);
      assertThat(r.signals()).containsExactly("agent:error");
      assertThat(r.rationale()).isEqualTo("agent timed out");
    }
  }

  @Nested
  class ComplianceVerdictTests {

    @Test
    void rejectsNullOutcome() {
      assertThatThrownBy(() -> new ComplianceVerdict(null, List.of(), "x"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("outcome");
    }

    @Test
    void rejectsNullCitations() {
      assertThatThrownBy(() -> new ComplianceVerdict(Outcome.PASS, null, "x"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("citations");
    }

    @Test
    void rejectsBlankRationale() {
      assertThatThrownBy(() -> new ComplianceVerdict(Outcome.PASS, List.of(), ""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rationale");
    }

    @Test
    void passAcceptsEmptyCitations() {
      assertThat(new ComplianceVerdict(Outcome.PASS, List.of(), "clean").citations()).isEmpty();
    }

    @Test
    void reviewFactoryProducesSentinel() {
      ComplianceVerdict v = ComplianceVerdict.review("mcp unreachable");
      assertThat(v.outcome()).isEqualTo(Outcome.REVIEW);
      assertThat(v.citations()).isEmpty();
      assertThat(v.rationale()).isEqualTo("mcp unreachable");
    }
  }

  @Nested
  class RouteRecommendationTests {

    @Test
    void rejectsBlankPspId() {
      assertThatThrownBy(() -> new RouteRecommendation("", "r", 0.9f, 30, "ok"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("pspId");
    }

    @Test
    void rejectsBlankRouteId() {
      assertThatThrownBy(() -> new RouteRecommendation("psp", "", 0.9f, 30, "ok"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("routeId");
    }

    @Test
    void rejectsSuccessRateAboveOne() {
      assertThatThrownBy(() -> new RouteRecommendation("psp", "r", 1.01f, 30, "ok"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expectedSuccessRate");
    }

    @Test
    void rejectsSuccessRateBelowZero() {
      assertThatThrownBy(() -> new RouteRecommendation("psp", "r", -0.01f, 30, "ok"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expectedSuccessRate");
    }

    @Test
    void rejectsNegativeCost() {
      assertThatThrownBy(() -> new RouteRecommendation("psp", "r", 0.9f, -1, "ok"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expectedCostBps");
    }

    @Test
    void acceptsBoundaryValues() {
      assertThat(new RouteRecommendation("psp", "r", 0.0f, 0, "x").expectedSuccessRate()).isZero();
      assertThat(new RouteRecommendation("psp", "r", 1.0f, 0, "x").expectedSuccessRate())
          .isEqualTo(1.0f);
    }
  }

  @Nested
  class DecisionTests {

    @Test
    void approvedFactoryWrapsRouteInOptional() {
      Decision d =
          Decision.approved(
              10,
              new ComplianceVerdict(Outcome.PASS, List.of(), "ok"),
              new RouteRecommendation("psp-c", "r-1", 0.95f, 30, "ok"),
              List.of("ok"));
      assertThat(d.outcome()).isEqualTo(DecisionOutcome.APPROVED);
      assertThat(d.route()).isPresent();
    }

    @Test
    void declinedFactoryReturnsEmptyRoute() {
      Decision d =
          Decision.declined(
              80, new ComplianceVerdict(Outcome.FAIL, List.of("SYN-1"), "hit"), List.of("hit"));
      assertThat(d.outcome()).isEqualTo(DecisionOutcome.DECLINED);
      assertThat(d.route()).isEmpty();
    }

    @Test
    void reviewFactoryReturnsEmptyRoute() {
      Decision d =
          Decision.review(
              60, new ComplianceVerdict(Outcome.PASS, List.of(), "ok"), List.of("mid risk"));
      assertThat(d.outcome()).isEqualTo(DecisionOutcome.REVIEW);
      assertThat(d.route()).isEmpty();
    }
  }
}
