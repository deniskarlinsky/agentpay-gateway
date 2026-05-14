package com.agentpay.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record IntentTokenResponse(
    @JsonProperty("intent_token") String intentToken,
    @JsonProperty("expires_at") Instant expiresAt) {}
