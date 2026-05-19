package com.agentpay.orchestrator.agents.support;

import com.agentpay.shared.pii.PiiRedactor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * NFR-S-003: PII redaction enforced at the prompt boundary. The Gateway already redacts request
 * bodies (FR-G-006); this advisor is the second-of-two defenses, scrubbing both system and user
 * messages before they leave the JVM. Reuses the same {@link PiiRedactor} regex set the Gateway
 * filter uses, so the two defenses stay in lockstep.
 *
 * <p>We do NOT redact the assistant response — Anthropic does not echo card numbers/IBANs back
 * unless we sent them, so redacting the input is sufficient. If a future agent prompt asks the
 * model to emit a PAN, this advisor will not help — that's a prompt-design problem.
 */
@Component
public class PiiRedactionAdvisor implements CallAdvisor {

  // Runs ahead of any Spring AI-internal advisor (chat-memory, observability) so downstream
  // advisors never see the unredacted prompt. HIGHEST_PRECEDENCE + 100 keeps room for higher-
  // priority custom advisors while staying well below Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER.
  private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    Prompt original = request.prompt();
    Prompt redacted =
        original
            .augmentSystemMessage(sys -> new SystemMessage(PiiRedactor.redact(sys.getText())))
            .augmentUserMessage(
                user -> UserMessage.builder().text(PiiRedactor.redact(user.getText())).build());
    ChatClientRequest mutated = request.mutate().prompt(redacted).build();
    return chain.nextCall(mutated);
  }

  @Override
  public String getName() {
    return "pii-redaction";
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}
