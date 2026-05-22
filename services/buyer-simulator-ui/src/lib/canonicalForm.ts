/**
 * Browser mirror of {@code com.agentpay.buyer.keys.CanonicalForm}. Produces
 * the exact bytes the gateway verifier reconstructs from the JSON request
 * body — pipe-separated UTF-8, in the order
 * {@code merchant_id | amount | currency | case_id}.
 *
 * The {@code amount} field MUST match {@code BigDecimal.toPlainString()} on
 * the Java side: no scientific notation, trailing zeros preserved verbatim.
 * Callers therefore pass a pre-normalised string (e.g. "42.50") rather than
 * a JavaScript number — number-to-string conversion would drop trailing
 * zeros and break signature verification.
 */
export function canonicalForm(
  merchantId: string,
  amountPlain: string,
  currency: string,
  caseId: string,
): Uint8Array {
  const s = `${merchantId}|${amountPlain}|${currency}|${caseId}`
  return new TextEncoder().encode(s)
}
