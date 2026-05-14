package com.agentpay.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record IntentTokenRequest(
    @JsonProperty("agent_id") String agentId,
    @JsonProperty("agent_pubkey") String agentPubkey,
    @JsonProperty("merchant_id") String merchantId,
    @JsonProperty("amount_cap") BigDecimal amountCap,
    @JsonProperty("currency") String currency,
    @JsonProperty("scope") String scope,
    @JsonProperty("ttl_seconds") Integer ttlSeconds) {}
