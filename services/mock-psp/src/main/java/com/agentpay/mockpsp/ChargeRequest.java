package com.agentpay.mockpsp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Fields: case_id (idempotency key), amount (positive decimal), currency (ISO 4217), psp_id (one of
 * psp-a, psp-b, psp-c).
 */
public record ChargeRequest(
    @JsonProperty("case_id") String caseId,
    @JsonProperty("amount") BigDecimal amount,
    @JsonProperty("currency") String currency,
    @JsonProperty("psp_id") String pspId) {}
