package com.agentpay.buyer.keys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads, or first-time-generates, the buyer agent's RSA-2048 signing keypair (FR-B-004). Persisted
 * as a two-block PEM file (private key + public key) at {@code ~/.agentpay/buyer-key.pem}; the
 * private block uses the same PKCS#8 envelope a fresh {@code KeyPairGenerator} produces, the public
 * block uses X.509 SubjectPublicKeyInfo (so the gateway's existing {@code IntentTokenService}
 * thumbprint logic accepts it verbatim).
 *
 * <p>On POSIX filesystems the file is set to {@code 0600}; on Windows the JDK's POSIX-attribute
 * support is absent and we fall back to default ACLs (acceptable for a local-only demo per
 * NFR-S-001).
 */
public final class BuyerKeyStore {

  private static final Logger log = LoggerFactory.getLogger(BuyerKeyStore.class);

  private static final String PRIVATE_HEADER = "-----BEGIN PRIVATE KEY-----";
  private static final String PRIVATE_FOOTER = "-----END PRIVATE KEY-----";
  private static final String PUBLIC_HEADER = "-----BEGIN PUBLIC KEY-----";
  private static final String PUBLIC_FOOTER = "-----END PUBLIC KEY-----";

  private final Path keyPath;

  public BuyerKeyStore(Path keyPath) {
    this.keyPath = keyPath;
  }

  /**
   * Returns the existing keypair if {@code keyPath} is readable; otherwise generates and persists a
   * new one.
   */
  public KeyPair loadOrCreate() {
    if (Files.exists(keyPath)) {
      try {
        return load();
      } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException e) {
        throw new IllegalStateException(
            "buyer key file at " + keyPath + " could not be parsed; delete it to regenerate", e);
      }
    }
    KeyPair fresh = generate();
    try {
      persist(fresh);
    } catch (IOException e) {
      throw new IllegalStateException("failed to persist buyer key to " + keyPath, e);
    }
    log.info("generated new buyer keypair at {}", keyPath);
    return fresh;
  }

  public static String publicKeyPem(RSAPublicKey key) {
    return wrap(PUBLIC_HEADER, PUBLIC_FOOTER, key.getEncoded());
  }

  private KeyPair load() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
    String pem = Files.readString(keyPath, StandardCharsets.UTF_8);
    byte[] privDer = extractBlock(pem, PRIVATE_HEADER, PRIVATE_FOOTER);
    byte[] pubDer = extractBlock(pem, PUBLIC_HEADER, PUBLIC_FOOTER);
    KeyFactory rsa = KeyFactory.getInstance("RSA");
    RSAPrivateKey priv = (RSAPrivateKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(privDer));
    RSAPublicKey pub = (RSAPublicKey) rsa.generatePublic(new X509EncodedKeySpec(pubDer));
    return new KeyPair(pub, priv);
  }

  private KeyPair generate() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA KeyPairGenerator unavailable", e);
    }
  }

  private void persist(KeyPair kp) throws IOException {
    Path parent = keyPath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    String pem =
        wrap(PRIVATE_HEADER, PRIVATE_FOOTER, kp.getPrivate().getEncoded())
            + "\n"
            + wrap(PUBLIC_HEADER, PUBLIC_FOOTER, kp.getPublic().getEncoded())
            + "\n";
    Files.writeString(
        keyPath,
        pem,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
    restrictPermissions(keyPath);
  }

  private static void restrictPermissions(Path path) {
    try {
      Set<PosixFilePermission> perms =
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(path, perms);
    } catch (UnsupportedOperationException | IOException ignored) {
      // Windows / non-POSIX FS — falls back to default ACLs (acceptable for local-only demo).
    }
  }

  private static String wrap(String header, String footer, byte[] der) {
    String b64 = Base64.getEncoder().encodeToString(der);
    StringBuilder sb = new StringBuilder(header.length() + footer.length() + b64.length() + 64);
    sb.append(header).append('\n');
    for (int i = 0; i < b64.length(); i += 64) {
      sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
    }
    sb.append(footer);
    return sb.toString();
  }

  private static byte[] extractBlock(String pem, String header, String footer) {
    int start = pem.indexOf(header);
    int end = pem.indexOf(footer);
    if (start < 0 || end < 0 || end <= start) {
      throw new IllegalStateException("PEM block not found: " + header);
    }
    String body = pem.substring(start + header.length(), end).replaceAll("\\s", "");
    return Base64.getDecoder().decode(body);
  }
}
