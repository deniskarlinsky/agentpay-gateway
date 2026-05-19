package com.agentpay.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Map;

public record PaymentRequest(
    @JsonProperty("case_id") String caseId,
    @JsonProperty("merchant_id") String merchantId,
    @JsonProperty("amount") BigDecimal amount,
    @JsonProperty("currency") String currency,
    @JsonProperty("description") String description,
    @JsonProperty("buyer_signature") String buyerSignature,
    // Iter 5: identity-namespaced metadata (buyer.name, buyer.country, merchant.country, ...).
    // Never PII — the PII redaction filter runs on the whole JSON body upstream and rewrites any
    // PAN/IBAN substrings before this record is materialised. Forwarded verbatim to the
    // orchestrator; the supervisor's specialist agents read it from PaymentContext.agentMetadata.
    @JsonProperty("agent_metadata") Map<String, String> agentMetadata) {}
