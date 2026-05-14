package com.agentpay.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaymentResponse(
    @JsonProperty("case_id") String caseId,
    @JsonProperty("status") String status,
    @JsonProperty("trace_url") String traceUrl) {}
