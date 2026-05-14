package com.agentpay.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentpay.gateway")
public record GatewayProperties(
    String issuer,
    String signingKeyPem,
    String signingKeyPath,
    int maxTtlSeconds,
    String traceUrlTemplate) {}
