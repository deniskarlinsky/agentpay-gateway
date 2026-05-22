package com.agentpay.gateway.config;

import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
        .cors(Customizer.withDefaults())
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
    return http.cors(Customizer.withDefaults())
        .csrf(AbstractHttpConfigurer::disable)
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

  /**
   * Task 3.5: CORS for the browser-based buyer-simulator-ui. Both filter chains call {@code
   * http.cors(Customizer.withDefaults())} which resolves this bean by name; Spring Security
   * recognises preflight (OPTIONS + Origin + Access-Control-Request-Method) and bypasses
   * authentication so the protected {@code POST /payments} preflight doesn't 401.
   *
   * <p>Origins are an explicit allow-list (no wildcard — rejected at startup). Methods are limited
   * to {@code GET/POST/OPTIONS} since the UI uses no others. Credentials disabled because the
   * bearer token is carried in {@code Authorization} on the response body of {@code POST
   * /intent-tokens} and echoed back per-request — no cookies, no session.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
    List<String> origins = properties.allowedOrigins();
    if (origins == null || origins.isEmpty()) {
      throw new IllegalArgumentException(
          "agentpay.cors.allowed-origins must be configured with at least one origin");
    }
    if (origins.contains("*")) {
      throw new IllegalArgumentException(
          "agentpay.cors.allowed-origins must not contain wildcard '*' — list explicit origins");
    }
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(origins);
    configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "Authorization"));
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(Duration.ofHours(1));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
