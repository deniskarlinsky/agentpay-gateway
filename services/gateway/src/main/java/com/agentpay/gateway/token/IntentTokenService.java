package com.agentpay.gateway.token;

import com.agentpay.gateway.api.IntentTokenRequest;
import com.agentpay.gateway.api.IntentTokenResponse;
import com.agentpay.gateway.config.GatewayProperties;
import com.agentpay.gateway.error.ValidationException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IntentTokenService {

  private final GatewayProperties properties;
  private final RSAKey signingKey;
  private final Clock clock;

  public IntentTokenService(GatewayProperties properties, RSAKey signingKey, Clock clock) {
    this.properties = properties;
    this.signingKey = signingKey;
    this.clock = clock;
  }

  public IntentTokenResponse issue(IntentTokenRequest request) {
    require(request.agentId(), "agent_id is required");
    require(request.agentPubkey(), "agent_pubkey is required");
    require(request.merchantId(), "merchant_id is required");
    if (request.amountCap() == null || request.amountCap().signum() <= 0) {
      throw new ValidationException("AMOUNT_INVALID", "amount_cap must be positive");
    }
    require(request.currency(), "currency is required");
    require(request.scope(), "scope is required");
    if (request.ttlSeconds() == null || request.ttlSeconds() <= 0) {
      throw new ValidationException("TTL_INVALID", "ttl_seconds must be positive");
    }
    if (request.ttlSeconds() > properties.maxTtlSeconds()) {
      throw new ValidationException(
          "TTL_TOO_LONG",
          "ttl_seconds must be ≤ " + properties.maxTtlSeconds());
    }

    String jkt = computeJkt(request.agentPubkey());

    Instant now = clock.instant();
    Instant exp = now.plusSeconds(request.ttlSeconds());
    String jti = UUID.randomUUID().toString();

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(properties.issuer())
            .subject(request.agentId())
            .audience(request.merchantId())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .jwtID(jti)
            .claim("amount_cap", request.amountCap().toPlainString())
            .claim("currency", request.currency())
            .claim("scope", request.scope())
            .claim("agent_pubkey_jkt", jkt)
            .build();

    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build();
    SignedJWT signed = new SignedJWT(header, claims);
    try {
      signed.sign(new RSASSASigner(signingKey));
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to sign intent token", e);
    }
    return new IntentTokenResponse(signed.serialize(), exp);
  }

  private static void require(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new ValidationException("FIELD_REQUIRED", message);
    }
  }

  private static String computeJkt(String pubKeyPem) {
    try {
      String body =
          pubKeyPem
              .replace("-----BEGIN PUBLIC KEY-----", "")
              .replace("-----END PUBLIC KEY-----", "")
              .replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(body);
      PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
      RSAKey jwk = new RSAKey.Builder((RSAPublicKey) pub).build();
      return jwk.computeThumbprint().toString();
    } catch (Exception e) {
      // Fallthrough to JWK-format parsing for clients that send a JWK JSON instead of PEM.
      try {
        return RSAKey.parse(pubKeyPem).computeThumbprint().toString();
      } catch (Exception inner) {
        throw new ValidationException(
            "PUBKEY_INVALID", "agent_pubkey must be a PEM-encoded RSA public key");
      }
    }
  }

}
