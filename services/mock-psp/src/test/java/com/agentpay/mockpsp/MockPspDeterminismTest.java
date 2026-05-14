package com.agentpay.mockpsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MockPspDeterminismTest {

  private MockPspService service;

  @BeforeEach
  void setUp() {
    PspProperties props =
        new PspProperties(
            Map.of(
                "psp-a", new PspProperties.ProfileConfig(0.95, 30),
                "psp-b", new PspProperties.ProfileConfig(0.88, 20),
                "psp-c", new PspProperties.ProfileConfig(0.98, 45)));
    service = new MockPspService(props);
  }

  @Test
  void sameCaseIdSamePspAlwaysSameOutcome() {
    var req = new ChargeRequest("determinism-case-001", BigDecimal.TEN, "USD", "psp-a");
    ChargeResponse first = service.charge(req);
    for (int i = 0; i < 20; i++) {
      ChargeResponse repeat = service.charge(req);
      assertThat(repeat.success()).isEqualTo(first.success());
      assertThat(repeat.authCode()).isEqualTo(first.authCode());
      assertThat(repeat.reasonCode()).isEqualTo(first.reasonCode());
    }
  }

  @Test
  void differentCaseIdsMayProduceDifferentOutcomes() {
    long successes =
        IntStream.range(0, 100)
            .mapToObj(
                i -> service.charge(new ChargeRequest("case-" + i, BigDecimal.TEN, "USD", "psp-c")))
            .filter(ChargeResponse::success)
            .count();
    // psp-c is 98%: with 100 cases at least some should succeed and at least one fail on average
    assertThat(successes).isGreaterThan(80).isLessThanOrEqualTo(100);
  }

  @ParameterizedTest
  @ValueSource(strings = {"psp-a", "psp-b", "psp-c"})
  void successRateWithin2pp(String pspId) {
    double expectedRate =
        switch (pspId) {
          case "psp-a" -> 0.95;
          case "psp-b" -> 0.88;
          case "psp-c" -> 0.98;
          default -> throw new IllegalArgumentException(pspId);
        };

    long successes =
        IntStream.range(0, 1000)
            .mapToObj(
                i ->
                    service.charge(
                        new ChargeRequest("rate-case-" + i, BigDecimal.TEN, "USD", pspId)))
            .filter(ChargeResponse::success)
            .count();

    double actualRate = successes / 1000.0;
    assertThat(actualRate)
        .as("Success rate for %s should be %.2f ± 0.02, got %.3f", pspId, expectedRate, actualRate)
        .isBetween(expectedRate - 0.02, expectedRate + 0.02);
  }

  @Test
  void failureResponseContainsIso20022ReasonCode() {
    // Brute-force a case_id that fails for psp-b (12% failure rate — try until we find one).
    ChargeResponse failure = null;
    for (int i = 0; i < 200; i++) {
      var resp =
          service.charge(new ChargeRequest("fail-probe-" + i, BigDecimal.TEN, "USD", "psp-b"));
      if (!resp.success()) {
        failure = resp;
        break;
      }
    }
    assertThat(failure).isNotNull();
    assertThat(failure.reasonCode()).isIn("AC01", "AM04", "DT03");
    assertThat(failure.authCode()).isNull();
    assertThat(failure.costBps()).isZero();
  }

  @Test
  void allThreeIso20022CodesAreReachable() {
    List<String> codes =
        IntStream.range(0, 1000)
            .mapToObj(
                i ->
                    service.charge(
                        new ChargeRequest("code-case-" + i, BigDecimal.TEN, "USD", "psp-b")))
            .filter(r -> !r.success())
            .map(ChargeResponse::reasonCode)
            .distinct()
            .toList();
    assertThat(codes).containsExactlyInAnyOrder("AC01", "AM04", "DT03");
  }

  @Test
  void unknownPspIdReturnsFailureWithAc01() {
    ChargeResponse resp =
        service.charge(new ChargeRequest("case-x", BigDecimal.TEN, "USD", "psp-unknown"));
    assertThat(resp.success()).isFalse();
    assertThat(resp.reasonCode()).isEqualTo("AC01");
  }
}
