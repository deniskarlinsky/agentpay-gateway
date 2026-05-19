package com.agentpay.evals.runners;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot bootstrap for the eval suite. Pulls in {@code
 * spring-ai-starter-model-anthropic} so {@link
 * org.springframework.ai.chat.client.ChatClient.Builder} is available for injection — nothing else
 * is on the eval classpath, so the context is the smallest possible boot that gives us a working
 * ChatClient against the real Anthropic API.
 *
 * <p>The eval module deliberately does NOT depend on {@code :services:orchestrator}. Doing so drags
 * in {@code spring-ai-starter-vector-store-pgvector} and {@code spring-ai-starter-mcp-client},
 * whose autoconfigs fire on classpath presence and demand a DataSource / live MCP server — neither
 * of which an eval run should require. Agent prompts are copied into the eval test resources at
 * build time via the {@code copyAgentPrompts} Gradle task.
 */
@SpringBootApplication(scanBasePackages = "com.agentpay.evals.runners")
public class EvalsTestApp {

  public static void main(String[] args) {
    SpringApplication.run(EvalsTestApp.class, args);
  }
}
