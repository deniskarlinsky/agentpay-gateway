package com.agentpay.gateway.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentpay.gateway.api.IntentTokenRequest;
import com.agentpay.gateway.api.IntentTokenResponse;
import com.agentpay.gateway.config.GatewayProperties;
import com.agentpay.gateway.error.ValidationException;
import com.agentpay.gateway.signing.SigningKeyLoader;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntentTokenServiceTest {

  private GatewayProperties props;
  private RSAKey signingKey;
  private Clock fixed;
  private IntentTokenService service;
  private String agentPubkeyPem;

  @BeforeEach
  void setUp(@TempDir Path tmp) throws Exception {
    props =
        new GatewayProperties("agentpay-gateway", "", tmp.resolve("k.pem").toString(), 300, "x");
    signingKey = SigningKeyLoader.load(props);
    fixed = Clock.fixed(Instant.parse("2026-05-14T10:00:00Z"), ZoneOffset.UTC);
    service = new IntentTokenService(props, signingKey, fixed);

    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    RSAPublicKey pub = (RSAPublicKey) gen.generateKeyPair().getPublic();
    String b64 = Base64.getEncoder().encodeToString(pub.getEncoded());
    agentPubkeyPem = "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
  }

  @Test
  void issuesTokenWithAllRequiredClaims() throws Exception {
    IntentTokenRequest req =
        new IntentTokenRequest(
            "agent-1",
            agentPubkeyPem,
            "merchant-acme",
            new BigDecimal("50.00"),
            "USD",
            "purchase:sku-42",
            300);

    IntentTokenResponse resp = service.issue(req);

    SignedJWT parsed = SignedJWT.parse(resp.intentToken());
    assertThat(parsed.verify(new RSASSAVerifier(signingKey.toRSAPublicKey()))).isTrue();
    assertThat(parsed.getJWTClaimsSet().getIssuer()).isEqualTo("agentpay-gateway");
    assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("agent-1");
    assertThat(parsed.getJWTClaimsSet().getAudience()).containsExactly("merchant-acme");
    assertThat(parsed.getJWTClaimsSet().getJWTID()).isNotBlank();
    assertThat(parsed.getJWTClaimsSet().getClaim("amount_cap")).isEqualTo("50.00");
    assertThat(parsed.getJWTClaimsSet().getClaim("currency")).isEqualTo("USD");
    assertThat(parsed.getJWTClaimsSet().getClaim("scope")).isEqualTo("purchase:sku-42");
    assertThat((String) parsed.getJWTClaimsSet().getClaim("agent_pubkey_jkt")).isNotBlank();
    long delta =
        parsed.getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond()
            - parsed.getJWTClaimsSet().getIssueTime().toInstant().getEpochSecond();
    assertThat(delta).isEqualTo(300L);
    assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
    assertThat(parsed.getHeader().getKeyID()).isEqualTo(signingKey.getKeyID());
  }

  @Test
  void rejectsTtlOverFiveMinutes() {
    IntentTokenRequest req =
        new IntentTokenRequest(
            "agent-1", agentPubkeyPem, "merchant-acme", new BigDecimal("50.00"), "USD", "p", 301);

    assertThatThrownBy(() -> service.issue(req))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("ttl_seconds")
        .extracting("errorCode")
        .isEqualTo("TTL_TOO_LONG");
  }

  @Test
  void rejectsMissingFields() {
    IntentTokenRequest req =
        new IntentTokenRequest(null, agentPubkeyPem, "m", new BigDecimal("1"), "USD", "p", 60);
    assertThatThrownBy(() -> service.issue(req))
        .isInstanceOf(ValidationException.class)
        .extracting("errorCode")
        .isEqualTo("FIELD_REQUIRED");
  }

  @Test
  void rejectsInvalidPubkey() {
    IntentTokenRequest req =
        new IntentTokenRequest("agent-1", "not-a-pem", "m", new BigDecimal("1"), "USD", "p", 60);
    assertThatThrownBy(() -> service.issue(req))
        .isInstanceOf(ValidationException.class)
        .extracting("errorCode")
        .isEqualTo("PUBKEY_INVALID");
  }
}
