package com.agentpay.shared.pii;

import java.util.regex.Pattern;

/**
 * Masks card numbers and IBANs in arbitrary text (request bodies, log lines, prompts). Reused by
 * the gateway's request filter and by the orchestrator's ChatClient advisor.
 */
public final class PiiRedactor {

  public static final String PAN_REPLACEMENT = "***REDACTED-PAN***";
  public static final String IBAN_REPLACEMENT = "***REDACTED-IBAN***";

  // 16-digit PAN, optionally separated by single spaces or hyphens between 4-digit groups.
  private static final Pattern PAN =
      Pattern.compile("(?<![0-9])\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?![0-9])");

  // IBAN: 2-letter ISO country code, 2 check digits, 11–30 BBAN chars (letters/digits).
  // Anchored on word boundaries so it does not chew through arbitrary uppercase identifiers.
  private static final Pattern IBAN =
      Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b");

  private PiiRedactor() {}

  public static String redact(String input) {
    if (input == null || input.isEmpty()) {
      return input;
    }
    String s = PAN.matcher(input).replaceAll(PAN_REPLACEMENT);
    return IBAN.matcher(s).replaceAll(IBAN_REPLACEMENT);
  }
}
