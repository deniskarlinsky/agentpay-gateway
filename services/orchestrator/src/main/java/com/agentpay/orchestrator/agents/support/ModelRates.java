package com.agentpay.orchestrator.agents.support;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-model token rates (NFR-COST-002). Configured under {@code agentpay.rates.<model>.*}. Cost is
 * (inputTokens / 1_000_000) * inputPerMtok + (outputTokens / 1_000_000) * outputPerMtok, rounded to
 * 6 decimal places to match the {@code cost_usd NUMERIC(10,6)} column.
 *
 * <p>Prefix is {@code agentpay} with field {@code rates} so the YAML reads naturally as {@code
 * agentpay.rates.claude-sonnet-4-6.input-per-mtok}. Other {@code agentpay.*} keys are unbound on
 * this bean — that's fine; they bind elsewhere (or are read via {@code @Value}).
 */
@ConfigurationProperties(prefix = "agentpay")
public class ModelRates {

  private static final BigDecimal MILLION = new BigDecimal("1000000");

  private Map<String, RateEntry> rates = Map.of();

  public Map<String, RateEntry> getRates() {
    return rates;
  }

  public void setRates(Map<String, RateEntry> rates) {
    this.rates = rates;
  }

  public BigDecimal calculate(String model, int inputTokens, int outputTokens) {
    RateEntry entry = rates.get(model);
    if (entry == null) {
      throw new IllegalArgumentException(
          "no rate configured for model='" + model + "' under agentpay.rates");
    }
    BigDecimal in =
        BigDecimal.valueOf(inputTokens)
            .divide(MILLION, MathContext.DECIMAL64)
            .multiply(entry.inputPerMtok());
    BigDecimal out =
        BigDecimal.valueOf(outputTokens)
            .divide(MILLION, MathContext.DECIMAL64)
            .multiply(entry.outputPerMtok());
    return in.add(out).setScale(6, RoundingMode.HALF_UP);
  }

  /** Per-model rate; keys are kebab-case (e.g. {@code claude-sonnet-4-6}). */
  public record RateEntry(BigDecimal inputPerMtok, BigDecimal outputPerMtok) {
    public RateEntry {
      if (inputPerMtok == null || inputPerMtok.signum() < 0) {
        throw new IllegalArgumentException("inputPerMtok must be non-negative");
      }
      if (outputPerMtok == null || outputPerMtok.signum() < 0) {
        throw new IllegalArgumentException("outputPerMtok must be non-negative");
      }
    }
  }
}
