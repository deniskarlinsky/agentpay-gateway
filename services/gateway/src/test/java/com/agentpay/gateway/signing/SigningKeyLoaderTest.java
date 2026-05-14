package com.agentpay.gateway.signing;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentpay.gateway.config.GatewayProperties;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeyLoaderTest {

  @Test
  void generatesAndPersistsKeyWhenAbsent(@TempDir Path tmp) throws Exception {
    Path keyPath = tmp.resolve("gateway-key.pem");
    GatewayProperties props =
        new GatewayProperties("agentpay-gateway", "", keyPath.toString(), 300, "x");

    RSAKey first = SigningKeyLoader.load(props);

    assertThat(Files.exists(keyPath)).isTrue();
    assertThat(first.toRSAPublicKey().getModulus().bitLength()).isEqualTo(2048);

    RSAKey second = SigningKeyLoader.load(props);
    assertThat(second.toRSAPublicKey().getModulus())
        .as("subsequent load reuses persisted key")
        .isEqualTo(first.toRSAPublicKey().getModulus());
  }

  @Test
  void prefersEnvPemOverFile(@TempDir Path tmp) throws Exception {
    Path keyPath = tmp.resolve("gateway-key.pem");
    GatewayProperties bootstrap =
        new GatewayProperties("agentpay-gateway", "", keyPath.toString(), 300, "x");
    RSAKey persisted = SigningKeyLoader.load(bootstrap);
    String envPem = Files.readString(keyPath);

    Path emptyDir = tmp.resolve("empty");
    Files.createDirectories(emptyDir);
    Path absentPath = emptyDir.resolve("missing.pem");
    GatewayProperties withEnv =
        new GatewayProperties("agentpay-gateway", envPem, absentPath.toString(), 300, "x");

    RSAKey fromEnv = SigningKeyLoader.load(withEnv);
    assertThat(fromEnv.toRSAPublicKey().getModulus())
        .isEqualTo(persisted.toRSAPublicKey().getModulus());
    assertThat(Files.exists(absentPath)).as("env path did not write a file").isFalse();
  }

  @Test
  void publicJwksContainsNoPrivateMaterial(@TempDir Path tmp) {
    GatewayProperties props =
        new GatewayProperties(
            "agentpay-gateway", "", tmp.resolve("k.pem").toString(), 300, "x");
    RSAKey key = SigningKeyLoader.load(props);

    assertThat(SigningKeyLoader.publicJwks(key).toString())
        .doesNotContain("\"d\":")
        .contains("\"n\":")
        .contains("\"e\":");
  }
}
