package com.agentpay.buyer.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import org.junit.jupiter.api.Test;

class CanonicalFormTest {

  @Test
  void bytesAreStableAndPipeDelimited() {
    byte[] form =
        CanonicalForm.bytes("merchant-acme", new BigDecimal("42.50"), "USD", "case-7f2a91c0");

    assertThat(new String(form, StandardCharsets.UTF_8))
        .isEqualTo("merchant-acme|42.50|USD|case-7f2a91c0");
  }

  @Test
  void amountIsRenderedPlain() {
    // Scientific notation must not leak into the canonical form: a verifier reconstructing the
    // bytes from the JSON request body would otherwise see a different string and reject the
    // signature.
    byte[] form = CanonicalForm.bytes("m", new BigDecimal("1E2"), "USD", "case-x");
    assertThat(new String(form, StandardCharsets.UTF_8)).isEqualTo("m|100|USD|case-x");
  }

  @Test
  void trailingZerosArePreserved() {
    // "42.50" must serialise as "42.50", not "42.5" — verifiers compare strings, not numbers.
    byte[] form = CanonicalForm.bytes("m", new BigDecimal("42.50"), "USD", "case-x");
    assertThat(new String(form, StandardCharsets.UTF_8)).isEqualTo("m|42.50|USD|case-x");
  }

  @Test
  void nullArgsRejected() {
    assertThatThrownBy(() -> CanonicalForm.bytes(null, BigDecimal.ONE, "USD", "case"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalForm.bytes("m", null, "USD", "case"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalForm.bytes("m", BigDecimal.ONE, null, "case"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalForm.bytes("m", BigDecimal.ONE, "USD", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void signatureRoundTripsAgainstRsaKeypair() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair kp = gen.generateKeyPair();

    byte[] form = CanonicalForm.bytes("merchant-acme", new BigDecimal("42.50"), "USD", "case-rt");

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(kp.getPrivate());
    signer.update(form);
    byte[] signed = signer.sign();

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(kp.getPublic());
    verifier.update(form);
    assertThat(verifier.verify(signed)).isTrue();
  }
}
