package com.agentpay.buyer;

import com.agentpay.buyer.http.GatewayClient;
import com.agentpay.buyer.keys.BuyerKeyStore;
import com.agentpay.buyer.keys.CanonicalForm;
import com.agentpay.buyer.scenarios.Scenario;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * CLI entry point (FR-B-001..004). Flags: {@code --merchant=<id>}, {@code --amount=<decimal>},
 * {@code --scenario=<happy|compliance-fail|review>}. Drives one payment from intent-token issuance
 * to terminal Saga state, polling {@code GET /cases/{id}} on a 30s / 1s budget. Prints the
 * orchestrator's trace URL once a terminal state is reached.
 *
 * <p>The application runs with {@code web-application-type=none}; this runner returns once the
 * payment lifecycle ends, and the {@link #exitCode} is what {@code System.exit} surfaces.
 */
@Component
public class BuyerClientRunner implements ApplicationRunner, ExitCodeGenerator {

  private static final Logger log = LoggerFactory.getLogger(BuyerClientRunner.class);

  private static final Set<String> TERMINAL =
      Set.of("COMMITTED", "DECLINED", "COMPENSATED", "SUSPENDED_FOR_REVIEW");

  private final String gatewayUrl;
  private final String langfuseBaseUrl;
  private final String agentId;
  private final int ttlSeconds;
  private final Path keyPath;

  private int exitCode = 0;

  public BuyerClientRunner(
      @Value("${agentpay.gateway.url}") String gatewayUrl,
      @Value("${agentpay.langfuse.base-url}") String langfuseBaseUrl,
      @Value("${agentpay.buyer.agent-id}") String agentId,
      @Value("${agentpay.buyer.intent-ttl-seconds}") int ttlSeconds,
      @Value("${agentpay.buyer.key-path}") String keyPath) {
    this.gatewayUrl = gatewayUrl;
    this.langfuseBaseUrl = langfuseBaseUrl;
    this.agentId = agentId;
    this.ttlSeconds = ttlSeconds;
    this.keyPath = Paths.get(keyPath);
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      runOnce(
          required(args, "merchant"),
          new BigDecimal(required(args, "amount")),
          Scenario.fromFlag(required(args, "scenario")));
    } catch (RuntimeException e) {
      log.error("buyer-client failed: {}", e.getMessage(), e);
      exitCode = 1;
    }
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  private void runOnce(String merchantId, BigDecimal amount, Scenario scenario) {
    String currency = "USD";
    String caseId = "case-" + UUID.randomUUID().toString().substring(0, 8);
    log.info(
        "scenario={} merchant={} amount={} case_id={}",
        scenario.flag(),
        merchantId,
        amount.toPlainString(),
        caseId);

    KeyPair keys = new BuyerKeyStore(keyPath).loadOrCreate();
    String pubKeyPem = BuyerKeyStore.publicKeyPem((RSAPublicKey) keys.getPublic());

    GatewayClient gateway = new GatewayClient(gatewayUrl);

    GatewayClient.IntentTokenResponse token =
        gateway.requestIntentToken(
            new GatewayClient.IntentTokenRequest(
                agentId,
                pubKeyPem,
                merchantId,
                // amount_cap matches amount for the demo — the gateway rejects amount > cap
                // (Scenario D); equality is the simplest path that exercises the cap check.
                amount,
                currency,
                "purchase:" + scenario.flag(),
                ttlSeconds));
    log.info("intent token issued exp={}", token.expiresAt());

    byte[] canonical = CanonicalForm.bytes(merchantId, amount, currency, caseId);
    String signature = signBase64(keys, canonical);

    GatewayClient.PaymentResponse submitted =
        gateway.submitPayment(
            token.intentToken(),
            new GatewayClient.PaymentRequest(
                caseId,
                merchantId,
                amount,
                currency,
                "scenario " + scenario.flag(),
                signature,
                scenario.agentMetadata()));
    log.info("payment accepted case_id={} status={}", submitted.caseId(), submitted.status());

    GatewayClient.CaseStatus terminal = pollUntilTerminal(gateway, submitted.caseId());
    System.out.printf(
        Locale.ROOT,
        "%n=== scenario=%s case_id=%s ===%nterminal_state=%s%nlangfuse_trace=%s%n",
        scenario.flag(),
        submitted.caseId(),
        terminal.state(),
        langfuseBaseUrl + "/trace/" + submitted.caseId());
  }

  private GatewayClient.CaseStatus pollUntilTerminal(GatewayClient gateway, String caseId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    GatewayClient.CaseStatus last = null;
    while (System.nanoTime() < deadline) {
      try {
        last = gateway.getCaseStatus(caseId);
        if (last != null && TERMINAL.contains(last.state())) {
          return last;
        }
      } catch (RuntimeException e) {
        log.warn("polling case_id={} failed: {}", caseId, e.getMessage());
      }
      sleep(Duration.ofSeconds(1));
    }
    throw new IllegalStateException(
        "case_id="
            + caseId
            + " did not reach a terminal state within 30s (last state="
            + (last == null ? "unknown" : last.state())
            + ")");
  }

  private static String signBase64(KeyPair keys, byte[] payload) {
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(keys.getPrivate());
      signer.update(payload);
      return Base64.getEncoder().encodeToString(signer.sign());
    } catch (Exception e) {
      throw new IllegalStateException("failed to sign payment payload", e);
    }
  }

  private static String required(ApplicationArguments args, String name) {
    var values = args.getOptionValues(name);
    if (values == null || values.isEmpty() || values.get(0).isBlank()) {
      throw new IllegalArgumentException(
          "missing required CLI flag --" + name + " (got args: " + args.getOptionNames() + ")");
    }
    return values.get(0);
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("buyer-client interrupted while polling", e);
    }
  }
}
