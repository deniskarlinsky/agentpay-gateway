package com.agentpay.sanctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class SanctionsMcpService {

  private static final Logger log = LoggerFactory.getLogger(SanctionsMcpService.class);

  private final ObjectMapper objectMapper;
  private List<SanctionEntry> entries;

  public SanctionsMcpService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void loadFixtures() throws Exception {
    try (InputStream is = getClass().getResourceAsStream("/fixtures/sanctions.json")) {
      entries = objectMapper.readValue(is, new TypeReference<>() {});
    }
    log.info("Loaded {} sanctions fixture entries", entries.size());
  }

  // TODO (Iteration 3): propagate case_id via W3C trace context (FR-M-006).
  // For now we log the trace ID only; case_id propagation arrives in Iteration 3.
  @McpTool(
      name = "lookup_sanctions",
      description = "Look up a person or entity name against synthetic sanctions/PEP fixture data.")
  public SanctionsResult lookupSanctions(
      @McpToolParam(description = "Full name", required = true) String name,
      @McpToolParam(description = "ISO 3166-1 alpha-2 country code") String country) {

    log.info("lookup_sanctions invoked: name='{}' country='{}'", name, country);

    for (SanctionEntry entry : entries) {
      if (!nameMatches(name, entry.name())) continue;
      if (country != null && !country.isBlank() && !country.equalsIgnoreCase(entry.country()))
        continue;

      float strength = name.equalsIgnoreCase(entry.name()) ? 1.0f : 0.85f;
      log.info(
          "sanctions HIT: name='{}' entry='{}' citationId='{}' strength={}",
          name,
          entry.name(),
          entry.citationId(),
          strength);
      return new SanctionsResult(true, strength, entry.listName(), entry.citationId());
    }

    return new SanctionsResult(false, null, null, null);
  }

  // Package-private for unit testing without Spring context.
  static boolean nameMatches(String query, String entryName) {
    String q = query.toLowerCase(Locale.ROOT);
    String e = entryName.toLowerCase(Locale.ROOT);
    if (q.equals(e)) return true;
    // Tolerate one edit only for fixture entries longer than 5 characters (FR-M-004).
    if (e.length() > 5 && levenshtein(q, e) <= 1) return true;
    return false;
  }

  static int levenshtein(String a, String b) {
    int m = a.length(), n = b.length();
    int[][] dp = new int[m + 1][n + 1];
    for (int i = 0; i <= m; i++) dp[i][0] = i;
    for (int j = 0; j <= n; j++) dp[0][j] = j;
    for (int i = 1; i <= m; i++) {
      for (int j = 1; j <= n; j++) {
        dp[i][j] =
            a.charAt(i - 1) == b.charAt(j - 1)
                ? dp[i - 1][j - 1]
                : 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
      }
    }
    return dp[m][n];
  }
}
