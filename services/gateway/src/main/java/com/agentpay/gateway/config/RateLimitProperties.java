package com.agentpay.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentpay.rate-limit")
public record RateLimitProperties(int requestsPerMinutePerAgent) {}
