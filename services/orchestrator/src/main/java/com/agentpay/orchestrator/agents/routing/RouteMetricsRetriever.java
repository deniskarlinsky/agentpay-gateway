package com.agentpay.orchestrator.agents.routing;

import com.agentpay.orchestrator.domain.PaymentContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * pgvector-backed top-K retrieval (FR-A-RT-002) feeding {@link
 * com.agentpay.orchestrator.agents.RoutingAgent}. The query string derives from the payment context
 * (currency, cross-border-ness, amount band) and is embedded by the same {@link
 * com.agentpay.orchestrator.agents.routing.VoyageEmbeddingModel} that seeded the corpus, so the
 * cosine-similarity match is meaningful.
 */
@Service
public class RouteMetricsRetriever {

  private final VectorStore vectorStore;

  public RouteMetricsRetriever(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  public List<RouteCandidate> retrieveTopK(PaymentContext ctx, int k) {
    SearchRequest request = SearchRequest.builder().query(buildQuery(ctx)).topK(k).build();
    List<Document> results = vectorStore.similaritySearch(request);
    if (results == null || results.isEmpty()) {
      return List.of();
    }
    return results.stream().map(RouteMetricsRetriever::toCandidate).toList();
  }

  /**
   * Renders the candidates as the {@code key=value} block format the routing.md prompt expects.
   * Every field RouteCandidate carries appears in the rendered block — the prompt's few-shot
   * examples reference all seven and the model treats missing keys as malformed input.
   */
  public String renderAsKeyValueBlock(List<RouteCandidate> candidates) {
    if (candidates.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(256 * candidates.size());
    for (int i = 0; i < candidates.size(); i++) {
      RouteCandidate c = candidates.get(i);
      sb.append("pspId=").append(c.pspId()).append('\n');
      sb.append("routeId=").append(c.routeId()).append('\n');
      sb.append("expectedSuccessRate=").append(c.expectedSuccessRate()).append('\n');
      sb.append("expectedCostBps=").append(c.expectedCostBps()).append('\n');
      sb.append("sampleSize=").append(c.sampleSize()).append('\n');
      sb.append("observedAt=").append(c.observedAt()).append('\n');
      sb.append("notes=").append(c.notes()).append('\n');
      if (i < candidates.size() - 1) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  private static String buildQuery(PaymentContext ctx) {
    String currency = ctx.currency() == null ? "USD" : ctx.currency();
    String band = amountBand(ctx.amount());
    String crossBorder = "domestic";
    if (ctx.agentMetadata() != null) {
      String buyerCountry = ctx.agentMetadata().get("buyer.country");
      String merchantCountry = ctx.agentMetadata().get("merchant.country");
      if (buyerCountry != null
          && merchantCountry != null
          && !buyerCountry.equals(merchantCountry)) {
        crossBorder = "cross-border";
      }
    }
    return "acquiring %s %s %s amount".formatted(currency, crossBorder, band);
  }

  private static String amountBand(BigDecimal amount) {
    if (amount == null) {
      return "small";
    }
    double d = amount.doubleValue();
    if (d < 10) return "micro";
    if (d < 100) return "small";
    if (d < 1000) return "medium";
    return "large";
  }

  private static RouteCandidate toCandidate(Document doc) {
    var meta = doc.getMetadata();
    return new RouteCandidate(
        stringOf(meta.get("pspId")),
        stringOf(meta.get("routeId")),
        doubleOf(meta.get("expectedSuccessRate")),
        intOf(meta.get("expectedCostBps")),
        intOf(meta.get("sampleSize")),
        stringOf(meta.get("observedAt")),
        stringOf(meta.get("notes")));
  }

  private static String stringOf(Object o) {
    return Objects.toString(o, "");
  }

  private static double doubleOf(Object o) {
    if (o instanceof Number n) return n.doubleValue();
    return Double.parseDouble(Objects.toString(o, "0"));
  }

  private static int intOf(Object o) {
    if (o instanceof Number n) return n.intValue();
    return Integer.parseInt(Objects.toString(o, "0"));
  }
}
