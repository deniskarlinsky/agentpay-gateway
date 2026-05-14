package com.agentpay.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentpay.gateway.api.IntentTokenResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GatewayApplicationTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static String agentPubkeyPem;
  private static String agentPubkeyPem2;

  @DynamicPropertySource
  static void redisProps(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @BeforeAll
  static void generatePubkeys() throws Exception {
    agentPubkeyPem = generatePem();
    agentPubkeyPem2 = generatePem();
  }

  private static String generatePem() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    RSAPublicKey pub = (RSAPublicKey) gen.generateKeyPair().getPublic();
    String b64 = Base64.getEncoder().encodeToString(pub.getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Test
  void agentCardEndpointReturnsExpectedSkills() throws Exception {
    mvc.perform(get("/.well-known/agent.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("AgentPay Gateway"))
        .andExpect(jsonPath("$.protocolVersion").value("0.3.0"))
        .andExpect(jsonPath("$.skills[0].id").value("request_intent_token"))
        .andExpect(jsonPath("$.skills[1].id").value("submit_payment"));
  }

  @Test
  void jwksEndpointReturnsRsaKey() throws Exception {
    mvc.perform(get("/.well-known/jwks.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
        .andExpect(jsonPath("$.keys[0].use").value("sig"))
        .andExpect(jsonPath("$.keys[0].n").exists())
        .andExpect(jsonPath("$.keys[0].e").exists())
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"d\""))));
  }

  @Test
  void scenarioDAmountExceedsCapReturns403() throws Exception {
    String token = issueToken("agent-D", "merchant-acme", "50.00", "USD", 300);

    String body = json.writeValueAsString(java.util.Map.of(
        "case_id", "case-d-1",
        "merchant_id", "merchant-acme",
        "amount", "75.00",
        "currency", "USD",
        "description", "over cap"));

    mvc.perform(
            post("/payments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error_code").value("SCOPE_AMOUNT_EXCEEDED"));
  }

  @Test
  void scenarioETokenReplayReturns403() throws Exception {
    String token = issueToken("agent-E", "merchant-acme", "50.00", "USD", 300);
    String body = json.writeValueAsString(java.util.Map.of(
        "case_id", "case-e-1",
        "merchant_id", "merchant-acme",
        "amount", "10.00",
        "currency", "USD",
        "description", "first use"));

    mvc.perform(
            post("/payments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.case_id").value("case-e-1"))
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    String body2 = json.writeValueAsString(java.util.Map.of(
        "case_id", "case-e-2",
        "merchant_id", "merchant-acme",
        "amount", "10.00",
        "currency", "USD",
        "description", "replay"));

    mvc.perform(
            post("/payments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body2))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error_code").value("TOKEN_REPLAYED"));
  }

  @Test
  void rateLimitReturns429AfterSixtyOneIntentTokenRequests() throws Exception {
    String body = json.writeValueAsString(java.util.Map.of(
        "agent_id", "agent-rl",
        "agent_pubkey", agentPubkeyPem,
        "merchant_id", "m",
        "amount_cap", "1.00",
        "currency", "USD",
        "scope", "p",
        "ttl_seconds", 60));

    for (int i = 0; i < 60; i++) {
      mvc.perform(post("/intent-tokens").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk());
    }
    mvc.perform(post("/intent-tokens").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.error_code").value("RATE_LIMIT_EXCEEDED"));
  }

  @Test
  void ttlOverFiveMinutesIsRejected() throws Exception {
    String body = json.writeValueAsString(java.util.Map.of(
        "agent_id", "agent-ttl",
        "agent_pubkey", agentPubkeyPem,
        "merchant_id", "m",
        "amount_cap", "1.00",
        "currency", "USD",
        "scope", "p",
        "ttl_seconds", 301));

    mvc.perform(post("/intent-tokens").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error_code").value("TTL_TOO_LONG"));
  }

  private String issueToken(String agentId, String merchantId, String amountCap, String currency, int ttl)
      throws Exception {
    String body = json.writeValueAsString(java.util.Map.of(
        "agent_id", agentId,
        "agent_pubkey", agentPubkeyPem2,
        "merchant_id", merchantId,
        "amount_cap", amountCap,
        "currency", currency,
        "scope", "p",
        "ttl_seconds", ttl));

    MvcResult result =
        mvc.perform(post("/intent-tokens").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode node = json.readTree(result.getResponse().getContentAsString());
    return node.get("intent_token").asText();
  }
}
