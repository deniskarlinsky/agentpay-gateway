package com.agentpay.orchestrator.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.DecisionOutcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupervisorTest {

  @Test
  void stubReturnsApprovedWithPspC() {
    Decision d =
        new Supervisor()
            .decide(
                new PaymentContext(
                    "case-stub",
                    "agent-1",
                    "merchant-1",
                    new BigDecimal("42.50"),
                    "USD",
                    "stub",
                    Map.of()));

    assertThat(d.outcome()).isEqualTo(DecisionOutcome.APPROVED);
    assertThat(d.route()).isPresent();
    assertThat(d.route().get().pspId()).isEqualTo("psp-c");
  }
}
