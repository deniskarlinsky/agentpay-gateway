package com.agentpay.orchestrator.agents.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the hand-rolled {@link VoyageEmbeddingModel} as the primary {@code EmbeddingModel} bean.
 * Spring AI's pgvector autoconfig picks this up automatically.
 */
@Configuration
public class VoyageEmbeddingConfig {

  @Bean
  VoyageEmbeddingModel voyageEmbeddingModel(
      ObjectMapper objectMapper,
      @Value("${agentpay.voyage.api-key:}") String apiKey,
      @Value("${agentpay.voyage.base-url:https://api.voyageai.com}") String baseUrl,
      @Value("${agentpay.voyage.model:voyage-3-lite}") String model,
      @Value("${agentpay.voyage.dimensions:512}") int dimensions) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    return new VoyageEmbeddingModel(http, objectMapper, apiKey, baseUrl, model, dimensions);
  }
}
