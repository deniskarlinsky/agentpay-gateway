package com.agentpay.orchestrator.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Forwarded payment payload from the gateway (REQUIREMENTS.md §7.2.2). PII is redacted upstream
 * (FR-G-006); agentMetadata never carries PII. Identity-namespaced keys (buyer.name, buyer.country,
 * merchant.name, merchant.country) are populated by the gateway / saga enrichment step.
 */
public record PaymentContext(
    String caseId,
    String agentId,
    String merchantId,
    BigDecimal amount,
    String currency,
    String description,
    Map<String, String> agentMetadata) {}
