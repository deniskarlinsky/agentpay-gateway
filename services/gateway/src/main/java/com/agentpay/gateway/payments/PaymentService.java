package com.agentpay.gateway.payments;

import com.agentpay.gateway.api.PaymentRequest;
import com.agentpay.gateway.api.PaymentResponse;
import com.agentpay.gateway.config.GatewayProperties;
import com.agentpay.gateway.error.ScopeException;
import com.agentpay.gateway.replay.ReplayStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private final ReplayStore replayStore;
  private final GatewayProperties properties;
  private final Clock clock;

  public PaymentService(ReplayStore replayStore, GatewayProperties properties, Clock clock) {
    this.replayStore = replayStore;
    this.properties = properties;
    this.clock = clock;
  }

  public PaymentResponse process(Jwt token, PaymentRequest request) {
    Instant now = clock.instant();

    // 1. Expiry — first, before any other work or Redis call (FR-G-003, NFR-S-006 ordering).
    Instant exp = token.getExpiresAt();
    if (exp == null || !exp.isAfter(now)) {
      throw new ScopeException("TOKEN_EXPIRED", "intent token is expired");
    }

    // 2. Audience must match merchant_id on payment body.
    Object audClaim = token.getClaim("aud");
    String tokenAud = firstAudience(audClaim);
    if (request.merchantId() == null || !request.merchantId().equals(tokenAud)) {
      throw new ScopeException(
          "AUDIENCE_MISMATCH", "payment merchant_id does not match token audience");
    }

    // 3. Currency must match.
    String tokenCurrency = token.getClaimAsString("currency");
    if (request.currency() == null || !request.currency().equals(tokenCurrency)) {
      throw new ScopeException("CURRENCY_MISMATCH", "payment currency does not match token currency");
    }

    // 4. amount ≤ amount_cap (Scenario D).
    String amountCapClaim = token.getClaimAsString("amount_cap");
    if (amountCapClaim == null) {
      throw new ScopeException("TOKEN_INVALID", "intent token missing amount_cap");
    }
    BigDecimal cap = new BigDecimal(amountCapClaim);
    if (request.amount() == null || request.amount().signum() <= 0) {
      throw new ScopeException("AMOUNT_INVALID", "payment amount must be positive");
    }
    if (request.amount().compareTo(cap) > 0) {
      throw new ScopeException(
          "SCOPE_AMOUNT_EXCEEDED",
          "payment amount " + request.amount().toPlainString() + " exceeds cap " + cap.toPlainString());
    }

    // 5. Replay — atomic SET NX EX. Must be last; we only consume jti if everything else checks out
    //    (Scenario E).
    String jti = token.getId();
    if (jti == null) {
      throw new ScopeException("TOKEN_INVALID", "intent token missing jti");
    }
    long ttl = exp.getEpochSecond() - now.getEpochSecond();
    if (!replayStore.claim(jti, ttl)) {
      throw new ScopeException("TOKEN_REPLAYED", "intent token has already been used");
    }

    // TODO (Iter 5, NFR-S-005): verify request.buyer_signature against agent_pubkey_jkt.

    String caseId = resolveCaseId(request.caseId());

    // TODO (Iter 3, FR-O-001..009): forward to orchestrator over HTTP. Body has already passed
    // through the PII redaction filter at this point.
    log.info(
        "WOULD FORWARD payment case_id={} agent={} merchant={} amount={} currency={} jti={}",
        caseId,
        token.getSubject(),
        tokenAud,
        request.amount().toPlainString(),
        request.currency(),
        jti);

    String traceUrl = properties.traceUrlTemplate().replace("{case_id}", caseId);
    return new PaymentResponse(caseId, "ACCEPTED", traceUrl);
  }

  private static String firstAudience(Object claim) {
    if (claim instanceof String s) {
      return s;
    }
    if (claim instanceof java.util.List<?> list && !list.isEmpty()) {
      Object first = list.get(0);
      return first != null ? first.toString() : null;
    }
    return null;
  }

  private static String resolveCaseId(String supplied) {
    if (supplied != null && !supplied.isBlank()) {
      return supplied;
    }
    return "case-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
