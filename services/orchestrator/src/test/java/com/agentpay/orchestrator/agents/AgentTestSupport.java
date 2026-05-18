package com.agentpay.orchestrator.agents;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** Shared helpers for building stub Spring AI {@link ChatResponse} objects in unit tests. */
final class AgentTestSupport {

  private AgentTestSupport() {}

  static ChatResponse stubResponse(String text, int inputTokens, int outputTokens) {
    Generation g = new Generation(new AssistantMessage(text));
    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().usage(new DefaultUsage(inputTokens, outputTokens)).build();
    return new ChatResponse(List.of(g), metadata);
  }
}
