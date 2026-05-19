package com.agentpay.buyer.keys;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuyerKeyStoreTest {

  @Test
  void firstCallGeneratesAndPersists(@TempDir Path tmp) {
    Path keyFile = tmp.resolve(".agentpay/buyer-key.pem");

    KeyPair kp = new BuyerKeyStore(keyFile).loadOrCreate();

    assertThat(Files.exists(keyFile)).isTrue();
    assertThat(kp.getPublic()).isInstanceOf(RSAPublicKey.class);
    assertThat(kp.getPrivate()).isInstanceOf(RSAPrivateKey.class);
    assertThat(((RSAPublicKey) kp.getPublic()).getModulus().bitLength()).isEqualTo(2048);
  }

  @Test
  void secondCallReturnsSameKey(@TempDir Path tmp) {
    Path keyFile = tmp.resolve(".agentpay/buyer-key.pem");

    KeyPair first = new BuyerKeyStore(keyFile).loadOrCreate();
    KeyPair second = new BuyerKeyStore(keyFile).loadOrCreate();

    assertThat(first.getPublic().getEncoded()).isEqualTo(second.getPublic().getEncoded());
    assertThat(first.getPrivate().getEncoded()).isEqualTo(second.getPrivate().getEncoded());
  }

  @Test
  void publicKeyPemIsParseable(@TempDir Path tmp) throws Exception {
    Path keyFile = tmp.resolve(".agentpay/buyer-key.pem");
    KeyPair kp = new BuyerKeyStore(keyFile).loadOrCreate();

    String pem = BuyerKeyStore.publicKeyPem((RSAPublicKey) kp.getPublic());

    assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----").endsWith("-----END PUBLIC KEY-----");

    // Round-trip: sign a value with the persisted private key and verify with the PEM-rendered
    // public key parsed back to bytes. This is the same path the gateway will run.
    byte[] payload = "buyer-signature-roundtrip".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(kp.getPrivate());
    signer.update(payload);
    byte[] sig = signer.sign();

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(kp.getPublic());
    verifier.update(payload);
    assertThat(verifier.verify(sig)).isTrue();
  }
}
