package com.agentpay.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Task 3.5: explicit allow-list of browser origins that may call the gateway. Defaults wired in
 * {@code application.yml} cover the Vite dev server (5173) and the docker-compose buyer-simulator
 * (3002). Override at runtime via {@code AGENTPAY_CORS_ALLOWED_ORIGINS=https://foo,https://bar}.
 * Wildcard {@code "*"} is rejected at startup — see {@link SecurityConfig#corsConfigurationSource}.
 */
@ConfigurationProperties(prefix = "agentpay.cors")
public record CorsProperties(List<String> allowedOrigins) {}
