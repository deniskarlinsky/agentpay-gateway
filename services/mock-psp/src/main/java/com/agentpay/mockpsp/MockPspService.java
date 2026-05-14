package com.agentpay.mockpsp;

import org.springframework.stereotype.Service;

@Service
public class MockPspService {

  private static final String[] FAILURE_CODES = {"AC01", "AM04", "DT03"};

  private final PspProperties properties;

  public MockPspService(PspProperties properties) {
    this.properties = properties;
  }

  public ChargeResponse charge(ChargeRequest request) {
    PspProperties.ProfileConfig profile = properties.profiles().get(request.pspId());
    if (profile == null) {
      return new ChargeResponse(request.caseId(), request.pspId(), false, null, "AC01", 0);
    }

    int threshold = (int) (profile.successRate() * 10_000);
    int bucket = bucket(request.caseId(), request.pspId());

    if (bucket < threshold) {
      String authCode = authCode(request.caseId(), request.pspId());
      return new ChargeResponse(
          request.caseId(), request.pspId(), true, authCode, null, profile.costBps());
    }

    // Map failure bucket to an ISO 20022 reason code so each code is reachable (FR-P-003).
    String reasonCode = FAILURE_CODES[(bucket - threshold) % FAILURE_CODES.length];
    return new ChargeResponse(request.caseId(), request.pspId(), false, null, reasonCode, 0);
  }

  // Package-private for unit testing.
  static int bucket(String caseId, String pspId) {
    // Math.floorMod guarantees non-negative result for positive divisor.
    return Math.floorMod((caseId + "|" + pspId).hashCode(), 10_000);
  }

  private static String authCode(String caseId, String pspId) {
    int h = Math.floorMod((caseId + "|" + pspId + "|auth").hashCode(), 0xFF_FFFF);
    return "AUTH-" + String.format("%06X", h);
  }
}
