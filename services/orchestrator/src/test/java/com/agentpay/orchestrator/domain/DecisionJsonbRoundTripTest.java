package com.agentpay.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Constraint #3 from the Iter 6 plan: {@link Decision} now carries a {@code budgetExceeded} boolean
 * (NFR-COST-001). PaymentSaga persists Decision via {@code ObjectMapper.valueToTree} on a JSONB
 * column; on resume it reads back via {@code treeToValue}. This test pins both directions of the
 * round-trip:
 *
 * <ol>
 *   <li>An Iter 4b.3-era JSONB blob without the field deserializes with {@code budgetExceeded =
 *       false} (Jackson defaults absent boolean primitives).
 *   <li>A new write round-trips the field exactly — true and false.
 * </ol>
 */
class DecisionJsonbRoundTripTest {

  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    mapper.registerModule(new Jdk8Module());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Test
  void existingRowWithoutBudgetExceededFieldReadsAsFalse() throws Exception {
    // Shape that Iter 4b.3 would have written for a clean APPROVED case: no budgetExceeded field
    // anywhere in the tree. PaymentSaga.hydrateDecision (via objectMapper.treeToValue) must default
    // the new field to false instead of throwing.
    String legacyJson =
        """
        {
          "outcome": "APPROVED",
          "riskScore": 12,
          "compliance": {
            "outcome": "PASS",
            "citations": [],
            "rationale": "clean"
          },
          "route": {
            "pspId": "psp-c",
            "routeId": "route-us-1",
            "expectedSuccessRate": 0.978,
            "expectedCostBps": 45,
            "rationale": "highest success"
          },
          "rationale": ["risk: clean", "compliance: clean", "routing: psp-c"]
        }
        """;
    JsonNode tree = mapper.readTree(legacyJson);

    Decision d = mapper.treeToValue(tree, Decision.class);

    assertThat(d.outcome()).isEqualTo(DecisionOutcome.APPROVED);
    assertThat(d.budgetExceeded()).as("missing field defaults to false").isFalse();
    assertThat(d.route()).isPresent();
    assertThat(d.route().get().pspId()).isEqualTo("psp-c");
  }

  @Test
  void budgetExceededFalseSurvivesValueToTreeAndBack() {
    Decision original =
        Decision.approved(
            12,
            new ComplianceVerdict(Outcome.PASS, List.of(), "clean"),
            new RouteRecommendation("psp-c", "route-us-1", 0.978f, 45, "highest success"),
            List.of("risk: clean", "compliance: clean", "routing: psp-c"));

    JsonNode tree = mapper.valueToTree(original);
    Decision rehydrated = mapper.convertValue(tree, Decision.class);

    assertThat(tree.has("budgetExceeded")).isTrue();
    assertThat(tree.get("budgetExceeded").asBoolean()).isFalse();
    assertThat(rehydrated).isEqualTo(original);
    assertThat(rehydrated.budgetExceeded()).isFalse();
  }

  @Test
  void budgetExceededTrueSurvivesValueToTreeAndBack() {
    Decision original =
        Decision.budgetReview(
            50,
            ComplianceVerdict.review("budget short-circuit"),
            List.of("budget exceeded: running=$0.00138 budget=$0.0005"));

    JsonNode tree = mapper.valueToTree(original);
    Decision rehydrated = mapper.convertValue(tree, Decision.class);

    assertThat(tree.get("budgetExceeded").asBoolean()).isTrue();
    assertThat(tree.get("outcome").asText()).isEqualTo("REVIEW");
    assertThat(rehydrated.budgetExceeded()).isTrue();
    assertThat(rehydrated.outcome()).isEqualTo(DecisionOutcome.REVIEW);
    assertThat(rehydrated.route()).isEmpty();
  }
}
