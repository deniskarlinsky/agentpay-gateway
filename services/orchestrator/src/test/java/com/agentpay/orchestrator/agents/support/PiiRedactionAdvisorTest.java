package com.agentpay.orchestrator.agents.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class PiiRedactionAdvisorTest {

  private static final String PAN = "4111-1111-1111-1111";
  private static final String IBAN = "DE89370400440532013000";

  @Test
  void redactsPanAndIbanInUserAndSystemMessagesBeforeChain() {
    PiiRedactionAdvisor advisor = new PiiRedactionAdvisor();
    CallAdvisorChain chain = mock(CallAdvisorChain.class);
    ChatClientResponse canned = mock(ChatClientResponse.class);
    when(chain.nextCall(any())).thenReturn(canned);

    Prompt original =
        new Prompt(
            List.of(
                new SystemMessage("system context " + IBAN),
                new UserMessage("Please charge card " + PAN + " for IBAN " + IBAN)));
    ChatClientRequest request = ChatClientRequest.builder().prompt(original).build();

    ChatClientResponse response = advisor.adviseCall(request, chain);

    assertThat(response).isSameAs(canned);
    ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
    org.mockito.Mockito.verify(chain).nextCall(captor.capture());
    Prompt forwarded = captor.getValue().prompt();
    String userText = forwarded.getUserMessage().getText();
    String systemText = forwarded.getSystemMessage().getText();

    assertThat(userText).doesNotContain(PAN);
    assertThat(userText).doesNotContain(IBAN);
    assertThat(userText).contains("***REDACTED-PAN***");
    assertThat(userText).contains("***REDACTED-IBAN***");
    assertThat(systemText).doesNotContain(IBAN);
    assertThat(systemText).contains("***REDACTED-IBAN***");
  }

  @Test
  void metadataConfirmsAdvisorNameAndOrder() {
    PiiRedactionAdvisor advisor = new PiiRedactionAdvisor();
    assertThat(advisor.getName()).isEqualTo("pii-redaction");
    // HIGHEST_PRECEDENCE + 100 — runs ahead of Spring AI internal advisors.
    assertThat(advisor.getOrder()).isLessThan(Integer.MIN_VALUE + 1000);
  }
}
