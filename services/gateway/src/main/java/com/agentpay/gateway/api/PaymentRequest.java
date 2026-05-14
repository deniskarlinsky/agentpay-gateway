package com.agentpay.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record PaymentRequest(
    @JsonProperty("case_id") String caseId,
    @JsonProperty("merchant_id") String merchantId,
    @JsonProperty("amount") BigDecimal amount,
    @JsonProperty("currency") String currency,
    @JsonProperty("description") String description,
    @JsonProperty("buyer_signature") String buyerSignature) {}
