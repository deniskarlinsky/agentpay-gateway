package com.agentpay.gateway.config;

import com.agentpay.gateway.signing.SigningKeyLoader;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SigningKeyConfig {

  @Bean
  public RSAKey gatewaySigningKey(GatewayProperties properties) {
    return SigningKeyLoader.load(properties);
  }

  @Bean
  public JWKSet gatewayJwkSet(RSAKey gatewaySigningKey) {
    return SigningKeyLoader.publicJwks(gatewaySigningKey);
  }
}
