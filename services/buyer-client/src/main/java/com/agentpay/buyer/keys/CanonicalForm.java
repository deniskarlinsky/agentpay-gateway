package com.agentpay.buyer.keys;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Canonical byte form a buyer agent signs over before submitting POST /payments (NFR-S-005). The
 * fields and order are: {@code merchant_id | amount | currency | case_id}. Pipe separators avoid
 * the ambiguity of concatenation. {@code amount} is normalised to {@code toPlainString} (no
 * scientific notation; trailing zeros preserved exactly as they appear on the wire) so a verifier
 * can reproduce the bytes from the JSON request body. {@code case_id} serves as the per-request
 * nonce — there is no separate nonce field on PaymentRequest.
 */
public final class CanonicalForm {

  private CanonicalForm() {}

  public static byte[] bytes(String merchantId, BigDecimal amount, String currency, String caseId) {
    if (merchantId == null || amount == null || currency == null || caseId == null) {
      throw new IllegalArgumentException(
          "canonical-form inputs must all be non-null (merchantId, amount, currency, caseId)");
    }
    String s = merchantId + "|" + amount.toPlainString() + "|" + currency + "|" + caseId;
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
