package com.agentpay.gateway.pii;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentpay.shared.pii.PiiRedactor;
import org.junit.jupiter.api.Test;

class PiiRedactorTest {

  @Test
  void redactsRawPan() {
    assertThat(PiiRedactor.redact("4111111111111111")).isEqualTo("***REDACTED-PAN***");
  }

  @Test
  void redactsPanWithSpacesAndHyphens() {
    assertThat(PiiRedactor.redact("4111 1111 1111 1111")).isEqualTo("***REDACTED-PAN***");
    assertThat(PiiRedactor.redact("4111-1111-1111-1111")).isEqualTo("***REDACTED-PAN***");
  }

  @Test
  void redactsPanEmbeddedInJson() {
    String input = "{\"card\":\"4111111111111111\",\"amount\":42}";
    assertThat(PiiRedactor.redact(input))
        .isEqualTo("{\"card\":\"***REDACTED-PAN***\",\"amount\":42}");
  }

  @Test
  void redactsIban() {
    assertThat(PiiRedactor.redact("GB82WEST12345698765432")).isEqualTo("***REDACTED-IBAN***");
  }

  @Test
  void doesNotTouchUnrelatedDigits() {
    assertThat(PiiRedactor.redact("amount=42.50 case=case-7f2a"))
        .isEqualTo("amount=42.50 case=case-7f2a");
  }

  @Test
  void redactsBothInOneString() {
    String input = "pan=4111111111111111 iban=GB82WEST12345698765432";
    assertThat(PiiRedactor.redact(input))
        .isEqualTo("pan=***REDACTED-PAN*** iban=***REDACTED-IBAN***");
  }

  @Test
  void handlesNullAndEmpty() {
    assertThat(PiiRedactor.redact(null)).isNull();
    assertThat(PiiRedactor.redact("")).isEmpty();
  }
}
