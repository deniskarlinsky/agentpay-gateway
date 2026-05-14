package com.agentpay.mockpsp;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mock-psp")
public record PspProperties(Map<String, ProfileConfig> profiles) {

  public record ProfileConfig(double successRate, int costBps) {}
}
