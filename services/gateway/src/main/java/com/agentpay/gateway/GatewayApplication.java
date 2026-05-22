package com.agentpay.gateway;

import com.agentpay.gateway.config.CorsProperties;
import com.agentpay.gateway.config.GatewayProperties;
import com.agentpay.gateway.config.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
  GatewayProperties.class,
  RateLimitProperties.class,
  CorsProperties.class
})
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
