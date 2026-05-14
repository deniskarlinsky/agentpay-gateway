package com.agentpay.gateway.signing;

import com.agentpay.gateway.config.GatewayProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SigningKeyLoader {

  private static final Logger log = LoggerFactory.getLogger(SigningKeyLoader.class);
  private static final String KEY_ID = "agentpay-gateway-1";

  private SigningKeyLoader() {}

  public static RSAKey load(GatewayProperties props) {
    String envPem = props.signingKeyPem();
    if (envPem != null && !envPem.isBlank()) {
      log.info("Loading gateway signing key from GATEWAY_SIGNING_KEY_PEM env var");
      return fromPem(envPem);
    }

    Path path = Paths.get(props.signingKeyPath());
    if (Files.exists(path)) {
      try {
        log.info("Loading gateway signing key from {}", path.toAbsolutePath());
        return fromPem(Files.readString(path, StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new IllegalStateException("Failed to read signing key at " + path, e);
      }
    }

    log.warn(
        "GATEWAY_SIGNING_KEY_PEM not set and {} absent; generating ephemeral RSA-2048 keypair (DEV ONLY)",
        path.toAbsolutePath());
    KeyPair pair = generate();
    String pem = toPkcs8Pem((RSAPrivateKey) pair.getPrivate());
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(path, pem, StandardCharsets.UTF_8);
      log.info("Wrote generated signing key to {}", path.toAbsolutePath());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to write generated signing key to " + path, e);
    }
    return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
        .privateKey((RSAPrivateKey) pair.getPrivate())
        .keyID(KEY_ID)
        .keyUse(KeyUse.SIGNATURE)
        .build();
  }

  public static JWKSet publicJwks(RSAKey key) {
    return new JWKSet(key.toPublicJWK());
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA not available", e);
    }
  }

  private static RSAKey fromPem(String pem) {
    String body =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(body);
    try {
      KeyFactory kf = KeyFactory.getInstance("RSA");
      RSAPrivateKey priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
      RSAPublicKey pub = derivePublic(priv);
      return new RSAKey.Builder(pub)
          .privateKey(priv)
          .keyID(KEY_ID)
          .keyUse(KeyUse.SIGNATURE)
          .build();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("Failed to parse RSA PKCS8 PEM", e);
    }
  }

  private static RSAPublicKey derivePublic(RSAPrivateKey priv) {
    try {
      if (priv instanceof java.security.interfaces.RSAPrivateCrtKey crt) {
        java.security.spec.RSAPublicKeySpec spec =
            new java.security.spec.RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
      }
      throw new IllegalStateException(
          "Loaded private key is not RSAPrivateCrtKey; cannot derive public key");
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("Failed to derive RSA public key", e);
    }
  }

  private static String toPkcs8Pem(RSAPrivateKey priv) {
    byte[] der = priv.getEncoded();
    String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
    return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
  }
}
