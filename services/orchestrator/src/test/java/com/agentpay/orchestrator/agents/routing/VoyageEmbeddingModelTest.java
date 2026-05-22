package com.agentpay.orchestrator.agents.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class VoyageEmbeddingModelTest {

  @Test
  void requestTimeoutIsTakenFromConstructor() {
    // The HTTP request timeout must be configurable so it can be tuned to live well under the
    // 10s Supervisor specialist budget — a hardcoded 30s makes the routing future time out
    // before Voyage gives up.
    Duration configured = Duration.ofSeconds(4);
    VoyageEmbeddingModel model =
        new VoyageEmbeddingModel(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            "key",
            "https://api.voyageai.com",
            "voyage-3-lite",
            512,
            configured);

    assertThat(model.requestTimeout()).isEqualTo(configured);
  }
}
