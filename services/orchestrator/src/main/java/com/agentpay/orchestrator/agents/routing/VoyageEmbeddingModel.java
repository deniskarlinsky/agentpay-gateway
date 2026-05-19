package com.agentpay.orchestrator.agents.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

/**
 * Spring AI 1.1.5 ships no Voyage AI embedding starter, so we implement {@link EmbeddingModel}
 * directly against {@code POST https://api.voyageai.com/v1/embeddings}. Roadmap (Iter 6): replace
 * with a first-class starter if/when Spring AI publishes one. The dimensions returned by
 * voyage-3-lite must match the {@code spring.ai.vectorstore.pgvector.dimensions} property and the
 * {@code vector(N)} column in V2__route_metrics_voyage_dims.sql.
 *
 * <p>The {@code dimensions()} default in the parent interface issues a probe call to the API.
 * That's wasteful and brittle at startup (e.g., test contexts with no Voyage key), so we override
 * it to return the configured value.
 */
public class VoyageEmbeddingModel implements EmbeddingModel {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final String apiKey;
  private final String baseUrl;
  private final String model;
  private final int dimensions;

  public VoyageEmbeddingModel(
      HttpClient http,
      ObjectMapper mapper,
      String apiKey,
      String baseUrl,
      String model,
      int dimensions) {
    this.http = http;
    this.mapper = mapper;
    this.apiKey = apiKey;
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.model = model;
    this.dimensions = dimensions;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<String> inputs = request.getInstructions();
    if (inputs == null || inputs.isEmpty()) {
      return new EmbeddingResponse(List.of(), new EmbeddingResponseMetadata());
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "VOYAGE_API_KEY is not configured; set agentpay.voyage.api-key (env VOYAGE_API_KEY)");
    }

    ObjectNode body = mapper.createObjectNode();
    ArrayNode inputArr = body.putArray("input");
    inputs.forEach(inputArr::add);
    body.put("model", model);
    // Voyage distinguishes "document" (corpus side) from "query" (search side); the seed
    // path always embeds corpus content. RouteMetricsRetriever uses the same model for the
    // query side, which is acceptable for voyage-3-lite — Voyage's docs note the input_type
    // hint primarily benefits the larger voyage-3 models, not voyage-3-lite.
    body.put("input_type", "document");

    HttpRequest req;
    try {
      req =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/v1/embeddings"))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
              .build();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to serialize Voyage embedding request", e);
    }

    HttpResponse<String> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException("Voyage embeddings call failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while calling Voyage embeddings", e);
    }
    if (resp.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "Voyage embeddings returned " + resp.statusCode() + ": " + resp.body());
    }

    JsonNode root;
    try {
      root = mapper.readTree(resp.body());
    } catch (IOException e) {
      throw new UncheckedIOException("failed to parse Voyage embeddings response", e);
    }
    JsonNode dataNode = root.path("data");
    List<Embedding> embeddings = new ArrayList<>(dataNode.size());
    for (int i = 0; i < dataNode.size(); i++) {
      JsonNode embeddingNode = dataNode.get(i).path("embedding");
      float[] vec = new float[embeddingNode.size()];
      for (int j = 0; j < vec.length; j++) {
        vec[j] = (float) embeddingNode.get(j).asDouble();
      }
      embeddings.add(new Embedding(vec, i));
    }
    return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
  }

  @Override
  public float[] embed(Document document) {
    return embed(getEmbeddingContent(document));
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
