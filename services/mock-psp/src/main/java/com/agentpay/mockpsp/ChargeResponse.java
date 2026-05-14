package com.agentpay.mockpsp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Fields: case_id, psp_id, success, auth_code (non-null on success), reason_code (ISO 20022 on
 * failure: AC01=invalid account, AM04=insufficient funds, DT03=invalid date), cost_bps (0 on
 * failure).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeResponse(
    @JsonProperty("case_id") String caseId,
    @JsonProperty("psp_id") String pspId,
    @JsonProperty("success") boolean success,
    @JsonProperty("auth_code") String authCode,
    @JsonProperty("reason_code") String reasonCode,
    @JsonProperty("cost_bps") int costBps) {}
