package com.agentpay.orchestrator.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Forwarded payment payload from the gateway. PII is already redacted upstream (FR-G-006). */
public record PaymentContext(
    String caseId,
    String agentId,
    String merchantId,
    BigDecimal amount,
    String currency,
    UUID intentTokenJti,
    String description) {}
