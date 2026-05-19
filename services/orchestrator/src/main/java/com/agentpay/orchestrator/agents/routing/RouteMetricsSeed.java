package com.agentpay.orchestrator.agents.routing;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent pre-seed of {@code route_metrics} (FR-A-RT-002). On every startup we count rows and
 * skip if the corpus is already populated; otherwise we embed three candidate descriptions via the
 * wired {@link VectorStore} (PgVectorStore → VoyageEmbeddingModel) and INSERT them.
 *
 * <p>Numbers come from FR-P-002 (mock-psp profiles psp-a/psp-b/psp-c) so the demonstration corpus
 * matches the three PSPs that will actually charge.
 */
@Component
public class RouteMetricsSeed implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(RouteMetricsSeed.class);

  private static final List<SeedRoute> SEEDS =
      List.of(
          new SeedRoute(
              "psp-a",
              "route-us-1",
              0.952,
              30,
              1240,
              "2026-05-10T00:00:00Z",
              "domestic USD; stable"),
          new SeedRoute(
              "psp-b",
              "route-us-1",
              0.881,
              20,
              980,
              "2026-05-10T00:00:00Z",
              "domestic USD; cheaper but lower success"),
          new SeedRoute(
              "psp-c",
              "route-us-1",
              0.978,
              45,
              520,
              "2026-05-10T00:00:00Z",
              "domestic USD; highest success, premium cost"));

  private final VectorStore vectorStore;
  private final JdbcTemplate jdbc;
  private final String voyageApiKey;

  public RouteMetricsSeed(
      VectorStore vectorStore,
      JdbcTemplate jdbc,
      @Value("${agentpay.voyage.api-key:}") String voyageApiKey) {
    this.vectorStore = vectorStore;
    this.jdbc = jdbc;
    this.voyageApiKey = voyageApiKey;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (voyageApiKey == null || voyageApiKey.isBlank()) {
      // Seed is best-effort: without a Voyage key we can't embed, but the app should still start.
      // Tests that exercise routing stub Voyage via WireMock and set the key to a non-blank value.
      log.info("VOYAGE_API_KEY not configured; skipping route_metrics seed");
      return;
    }
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM route_metrics", Integer.class);
    if (count != null && count >= SEEDS.size()) {
      log.info("route_metrics already populated ({} rows); skipping seed", count);
      return;
    }
    List<Document> documents = SEEDS.stream().map(RouteMetricsSeed::toDocument).toList();
    try {
      vectorStore.add(documents);
      log.info("Seeded {} route candidates into pgvector", documents.size());
    } catch (RuntimeException e) {
      // Best-effort seed: Spring Boot fails the whole app if an ApplicationRunner throws. If the
      // embedding provider or pgvector is unreachable at startup we'd rather degrade gracefully
      // (routing runs against an empty corpus and RoutingAgent returns its retry-exhausted safe
      // pick) than crash the orchestrator. The seed retries idempotently on the next restart.
      log.warn(
          "route_metrics seed failed; continuing with an empty corpus. RoutingAgent will use"
              + " the safe-pick fallback until the seed succeeds on a later restart. error={}",
          e.toString());
    }
  }

  private static Document toDocument(SeedRoute r) {
    String content =
        "%s %s acquiring USD domestic; expected success %.3f; cost %d bps; sample size %d; %s"
            .formatted(
                r.pspId(),
                r.routeId(),
                r.expectedSuccessRate(),
                r.expectedCostBps(),
                r.sampleSize(),
                r.notes());
    Map<String, Object> metadata =
        Map.of(
            "pspId",
            r.pspId(),
            "routeId",
            r.routeId(),
            "expectedSuccessRate",
            r.expectedSuccessRate(),
            "expectedCostBps",
            r.expectedCostBps(),
            "sampleSize",
            r.sampleSize(),
            "observedAt",
            r.observedAt(),
            "notes",
            r.notes());
    return new Document(UUID.randomUUID().toString(), content, metadata);
  }

  private record SeedRoute(
      String pspId,
      String routeId,
      double expectedSuccessRate,
      int expectedCostBps,
      int sampleSize,
      String observedAt,
      String notes) {}
}
