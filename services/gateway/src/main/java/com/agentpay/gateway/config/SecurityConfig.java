package com.agentpay.gateway.config;

import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/**").permitAll()
                    .requestMatchers("/.well-known/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/intent-tokens").permitAll()
                    .requestMatchers(HttpMethod.POST, "/payments").authenticated()
                    .anyRequest().denyAll())
        .oauth2ResourceServer(oauth -> oauth.jwt(org.springframework.security.config.Customizer.withDefaults()))
        .build();
  }

  @Bean
  public JwtDecoder jwtDecoder(RSAKey signingKey) throws Exception {
    RSAPublicKey pub = signingKey.toRSAPublicKey();
    return NimbusJwtDecoder.withPublicKey(pub).signatureAlgorithm(SignatureAlgorithm.RS256).build();
  }
}
