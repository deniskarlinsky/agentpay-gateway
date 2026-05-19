package com.agentpay.gateway.config;

import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two security chains so the OAuth2 resource server's BearerTokenAuthenticationEntryPoint only
 * binds to the protected surface. Iter 5 hotfix: with a single chain, oauth2ResourceServer
 * installed its 401-emitting entry point on the entire filter chain, and unauthenticated GETs to
 * {@code /cases/{id}} came back 401 with {@code WWW-Authenticate: Bearer} instead of the controller
 * response — even though authorizeHttpRequests had permitAll on the path. Splitting the chains
 * keeps the bearer-token filter scoped to {@code /payments} (and anything that falls through to the
 * default-matched chain).
 */
@Configuration
public class SecurityConfig {

  /**
   * Public surface — no oauth2ResourceServer, so missing/invalid bearer tokens don't cause 401. The
   * buyer-client polls {@code GET /cases/{id}} unauthenticated; the intent token has already been
   * single-use-consumed by POST /payments at this point and there's nothing else for the client to
   * present.
   */
  @Bean
  @Order(1)
  public SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
    return http.securityMatchers(
            matchers ->
                matchers
                    .requestMatchers("/actuator/**")
                    .requestMatchers("/.well-known/**")
                    .requestMatchers(HttpMethod.POST, "/intent-tokens")
                    .requestMatchers(HttpMethod.GET, "/cases/**"))
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }

  /**
   * Protected surface — default {@code anyRequest} matcher catches everything the public chain
   * didn't, with oauth2ResourceServer applied. POST /payments is the only endpoint that requires
   * authentication today; the {@code denyAll} backstop keeps any future-added path inaccessible
   * until it's explicitly wired here or in the public chain.
   */
  @Bean
  @Order(2)
  public SecurityFilterChain protectedChain(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.POST, "/payments")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
        .build();
  }

  @Bean
  public JwtDecoder jwtDecoder(RSAKey signingKey) throws Exception {
    RSAPublicKey pub = signingKey.toRSAPublicKey();
    return NimbusJwtDecoder.withPublicKey(pub).signatureAlgorithm(SignatureAlgorithm.RS256).build();
  }
}
