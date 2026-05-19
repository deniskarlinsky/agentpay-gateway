package com.agentpay.buyer.scenarios;

import java.util.Map;

/**
 * Pre-baked demo scenarios driven by the {@code --scenario} CLI flag. Each preset supplies the
 * identity-namespaced metadata (FR-A-C-002: ComplianceAgent uses buyer.name/country and
 * merchant.name/country; FR-A-R: RiskAgent uses buyer.country, merchant.country, plus the agent's
 * own amount/currency-derived crossBorder/amountBand) that pushes the decision plane toward the
 * expected terminal Saga state.
 *
 * <p>The {@code review} preset's outcome is best-effort against the live Sonnet 4.6 model — there
 * are no real velocity_check / fraud_rules_lookup tools wired yet (see RiskAgent javadoc), so the
 * 50–79 band is a probabilistic prompt-calibration result rather than a hard contract. The other
 * two presets are deterministic: compliance-fail keys off the sanctions MCP fixture, and happy
 * carries no risk signals.
 */
public enum Scenario {
  HAPPY(
      "happy",
      "Alice Buyer",
      Map.of(
          "buyer.name", "Alice Buyer",
          "buyer.country", "US",
          "merchant.country", "US",
          "crossBorder", "false",
          "amountBand", "small")),

  // "Fictitious Bad Actor" is one of the synthetic SDN entries in
  // services/sanctions-mcp/src/main/resources/fixtures/sanctions.json (citationId SYN-021). The
  // ComplianceAgent calls lookup_sanctions(buyer.name, buyer.country) on every case and emits FAIL
  // on positive match → Decision.outcome = DECLINED.
  COMPLIANCE_FAIL(
      "compliance-fail",
      "Fictitious Bad Actor",
      Map.of(
          "buyer.name", "Fictitious Bad Actor",
          "buyer.country", "XX",
          "merchant.name", "Acme Widgets Ltd",
          "merchant.country", "US",
          "crossBorder", "true",
          "amountBand", "small")),

  // Cross-border + large amount + a recent first-seen agent are the calibration levers in
  // services/orchestrator/src/main/resources/prompts/risk.md that push the score into the REVIEW
  // band (50–79). Without real velocity / fraud-rules tools wired this is best-effort — the model
  // produces its score from the prompt and PaymentContext alone.
  REVIEW(
      "review",
      "Risky Reviewer",
      Map.of(
          "buyer.name", "Risky Reviewer",
          "buyer.country", "GB",
          "merchant.name", "Acme Widgets Ltd",
          "merchant.country", "US",
          "crossBorder", "true",
          "amountBand", "large",
          // The prompt explicitly uses firstSeenAt as a recency signal — keep it close to now.
          "buyer.firstSeenAt", "2026-05-18T09:00:00Z"));

  private final String flag;
  private final String buyerName;
  private final Map<String, String> agentMetadata;

  Scenario(String flag, String buyerName, Map<String, String> agentMetadata) {
    this.flag = flag;
    this.buyerName = buyerName;
    this.agentMetadata = agentMetadata;
  }

  public String flag() {
    return flag;
  }

  public String buyerName() {
    return buyerName;
  }

  public Map<String, String> agentMetadata() {
    return agentMetadata;
  }

  public static Scenario fromFlag(String s) {
    for (Scenario sc : values()) {
      if (sc.flag.equalsIgnoreCase(s)) {
        return sc;
      }
    }
    throw new IllegalArgumentException(
        "unknown scenario: " + s + " (expected one of happy, compliance-fail, review)");
  }
}
