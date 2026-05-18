package com.agentpay.orchestrator.agents.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelRatesTest {

  @Test
  void calculatesCostForSonnet() {
    // Sonnet 4.6: $3 / 1M input, $15 / 1M output. 1000 input + 500 output =
    // 1000/1M * 3 = 0.003, 500/1M * 15 = 0.0075 → 0.0105 USD.
    ModelRates rates = new ModelRates();
    rates.setRates(
        Map.of(
            "claude-sonnet-4-6",
            new ModelRates.RateEntry(new BigDecimal("3.00"), new BigDecimal("15.00"))));

    BigDecimal cost = rates.calculate("claude-sonnet-4-6", 1000, 500);
    assertThat(cost).isEqualByComparingTo("0.010500");
  }

  @Test
  void calculatesCostForHaiku() {
    // Haiku 4.5: $1 / 1M input, $5 / 1M output. 2000 input + 1000 output =
    // 2000/1M * 1 = 0.002, 1000/1M * 5 = 0.005 → 0.007 USD.
    ModelRates rates = new ModelRates();
    rates.setRates(
        Map.of(
            "claude-haiku-4-5",
            new ModelRates.RateEntry(new BigDecimal("1.00"), new BigDecimal("5.00"))));

    BigDecimal cost = rates.calculate("claude-haiku-4-5", 2000, 1000);
    assertThat(cost).isEqualByComparingTo("0.007000");
  }

  @Test
  void throwsWhenModelUnknown() {
    ModelRates rates = new ModelRates();
    rates.setRates(Map.of());
    assertThatThrownBy(() -> rates.calculate("gpt-4", 100, 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gpt-4");
  }

  @Test
  void zeroTokensZeroCost() {
    ModelRates rates = new ModelRates();
    rates.setRates(
        Map.of("m", new ModelRates.RateEntry(new BigDecimal("3.00"), new BigDecimal("15.00"))));
    assertThat(rates.calculate("m", 0, 0)).isEqualByComparingTo("0.000000");
  }
}
