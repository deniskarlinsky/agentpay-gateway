package com.agentpay.gateway.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentpay.gateway.orchestrator.OrchestratorClient;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Task 3.5: CORS must permit the buyer-simulator-ui (Vite dev server at 5173, docker-compose at
 * 3002) to call the gateway from the browser. Both filter chains must apply the same CORS
 * configuration — otherwise OPTIONS preflight to the protected {@code POST /payments} hits the
 * OAuth2 entry point and 401s before CORS headers are written.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CorsConfigTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProps(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired private MockMvc mvc;

  // Same pattern as GatewayApplicationTest — the orchestrator client is the one collaborator that
  // would otherwise reach out over HTTP at context startup.
  @MockitoBean private OrchestratorClient orchestratorClient;

  @Test
  void preflight_for_intent_tokens_is_allowed_from_vite_origin() throws Exception {
    mvc.perform(
            options("/intent-tokens")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(header().string("Access-Control-Allow-Methods", Matchers.containsString("POST")))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Headers",
                    Matchers.containsStringIgnoringCase("Content-Type")));
  }

  @Test
  void preflight_for_payments_bypasses_oauth2_entry_point() throws Exception {
    // The whole point of wiring cors(Customizer.withDefaults()) onto the protected chain: Spring
    // Security must recognise this as a preflight and short-circuit before the bearer-token filter
    // can return 401. No Authorization header is sent — preflights never carry credentials.
    mvc.perform(
            options("/payments")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type, authorization"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Headers",
                    Matchers.containsStringIgnoringCase("Authorization")));
  }

  @Test
  void preflight_from_unlisted_origin_is_rejected() throws Exception {
    // The important assertion is that the unlisted origin is NOT echoed back. Spring's default
    // for a denied origin is 403 with no CORS headers — but the security guarantee is the absent
    // Access-Control-Allow-Origin header, not the status code.
    mvc.perform(
            options("/intent-tokens")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
