package com.agentpay.gateway.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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

/**
 * Live-demo regression: the existing {@link com.agentpay.gateway.GatewayApplicationTest} stubs
 * {@code OrchestratorClient} at the bean level, so every gateway test has skipped the actual HTTP
 * forward to the orchestrator. That blind spot let the snake_case wire-contract bug ship — every
 * Scenario A payment returned terminal_state=COMPENSATED in production because the orchestrator was
 * sending camelCase JSON to mock-psp (see {@link
 * com.agentpay.orchestrator.psp.MockPspWireContractIT}). This IT plugs the gap on the
 * gateway↔orchestrator hop with a real WireMock-backed orchestrator and asserts:
 *
 * <ol>
 *   <li>The request body the gateway sends matches the orchestrator's {@code /internal/payments}
 *       contract — snake_case keys, all required fields present, {@code agent_metadata} threaded
 *       through verbatim.
 *   <li>The terminal state the orchestrator reports is propagated to the caller as-is, NOT a
 *       synthesised default.
 *   <li>When the orchestrator is unreachable, the gateway returns HTTP 502 with a meaningful error
 *       code, NOT a fake 200 that looks like a successful COMPENSATED.
 * </ol>
 *
 * <p>This is the test that would have caught the live-demo bug if it had existed in Iter 5.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GatewayOrchestratorIntegrationIT {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @RegisterExtension
  static final WireMockExtension ORCHESTRATOR =
      WireMockExtension.newInstance()
          // JDK 21 HttpClient negotiates HTTP/2 by default and WireMock 3.10's HTTP/2 path tears
          // the response stream on RestClient's blocking read ("Received RST_STREAM: Stream
          // cancelled"). Forcing HTTP/1.1 only on the WireMock side avoids the negotiation entirely
          // and matches what the production gateway sees against the orchestrator's Tomcat (also
          // HTTP/1.1 by default).
          .options(
              com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                  .dynamicPort()
                  .http2PlainDisabled(true))
          .build();

  private static String agentPubkeyPem;

  @BeforeAll
  static void generatePubkey() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    RSAPublicKey pub = (RSAPublicKey) gen.generateKeyPair().getPublic();
    String b64 = Base64.getEncoder().encodeToString(pub.getEncoded());
    agentPubkeyPem = "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    // Point the real OrchestratorClient bean at WireMock — no MockitoBean override here, so the
    // gateway makes an actual HTTP call. This is the difference from GatewayApplicationTest.
    registry.add("agentpay.orchestrator.url", ORCHESTRATOR::baseUrl);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Test
  void happyPathForwardsSnakeCaseAndPropagatesOrchestratorTerminalState() throws Exception {
    // Orchestrator returns a SUSPENDED_FOR_REVIEW state — any non-default value works, point is
    // that the gateway must propagate it verbatim rather than synthesising COMPENSATED or similar.
    ORCHESTRATOR.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/internal/payments"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"case_id\":\"case-e2e-happy\",\"status\":\"SUSPENDED_FOR_REVIEW\",\"duplicate\":false}")));

    String token = issueToken("agent-e2e", "merchant-acme", "100.00", "USD", 300);
    String body =
        json.writeValueAsString(
            java.util.Map.of(
                "case_id",
                "case-e2e-happy",
                "merchant_id",
                "merchant-acme",
                "amount",
                "42.50",
                "currency",
                "USD",
                "description",
                "happy",
                "agent_metadata",
                java.util.Map.of(
                    "buyer.name", "Alice", "buyer.country", "US", "merchant.country", "US")));

    mvc.perform(
            post("/payments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.case_id").value("case-e2e-happy"))
        // The status MUST match what the orchestrator reported — no default.
        .andExpect(jsonPath("$.status").value("SUSPENDED_FOR_REVIEW"));

    // Snake_case wire-contract assertions on the body the gateway actually sent. If anyone adds
    // a field to OrchestratorClient.Request without an @JsonProperty, this fails — locking down
    // the same class of bug the orchestrator's MockPspClient shipped with. JsonPath presence-only
    // checks for fields where the exact representation isn't the point (e.g. amount may render as
    // 42.5 or 42.50 depending on the BigDecimal mantissa).
    ORCHESTRATOR.verify(
        postRequestedFor(urlPathEqualTo("/internal/payments"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(matchingJsonPath("$.case_id", equalTo("case-e2e-happy")))
            .withRequestBody(matchingJsonPath("$.agent_id", equalTo("agent-e2e")))
            .withRequestBody(matchingJsonPath("$.merchant_id", equalTo("merchant-acme")))
            .withRequestBody(matchingJsonPath("$.amount"))
            .withRequestBody(matchingJsonPath("$.currency", equalTo("USD")))
            .withRequestBody(matchingJsonPath("$.intent_token_jti"))
            .withRequestBody(matchingJsonPath("$.agent_metadata.['buyer.name']", equalTo("Alice")))
            .withRequestBody(
                matchingJsonPath("$.agent_metadata.['buyer.country']", equalTo("US"))));
  }

  @Test
  void orchestratorUnreachableReturnsBadGatewayNotSynthesizedSuccess() throws Exception {
    // Stub a 5xx so the OrchestratorClient.forward path takes the RestClientResponseException
    // branch → OrchestratorUnavailableException → GlobalExceptionHandler → 502.
    ORCHESTRATOR.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/internal/payments"))
            .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

    String token = issueToken("agent-down", "merchant-acme", "100.00", "USD", 300);
    String body =
        json.writeValueAsString(
            java.util.Map.of(
                "case_id",
                "case-e2e-down",
                "merchant_id",
                "merchant-acme",
                "amount",
                "10.00",
                "currency",
                "USD",
                "description",
                "orchestrator down"));

    // 502 + ORCHESTRATOR_UNAVAILABLE — distinguishable from any 2xx/COMPENSATED so a buyer-client
    // cannot mistake "downstream failed" for "payment compensated".
    mvc.perform(
            post("/payments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error_code").value("ORCHESTRATOR_UNAVAILABLE"));
  }

  private String issueToken(
      String agentId, String merchantId, String amountCap, String currency, int ttl)
      throws Exception {
    String body =
        json.writeValueAsString(
            java.util.Map.of(
                "agent_id", agentId,
                "agent_pubkey", agentPubkeyPem,
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
