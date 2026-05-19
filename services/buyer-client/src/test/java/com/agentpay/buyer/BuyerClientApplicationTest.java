package com.agentpay.buyer;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentpay.buyer.scenarios.Scenario;
import org.junit.jupiter.api.Test;

class BuyerClientApplicationTest {

  @Test
  void scenarioPresetCarriesIdentityMetadata() {
    // Smoke test: the three scenarios remain wired with the metadata keys the orchestrator's
    // ComplianceAgent / RiskAgent expect to read off PaymentContext.agentMetadata.
    assertThat(Scenario.fromFlag("happy").agentMetadata())
        .containsKeys("buyer.name", "buyer.country", "merchant.country");
    assertThat(Scenario.fromFlag("compliance-fail").agentMetadata())
        .containsEntry("buyer.name", "Fictitious Bad Actor");
    assertThat(Scenario.fromFlag("review").agentMetadata()).containsEntry("crossBorder", "true");
  }
}
