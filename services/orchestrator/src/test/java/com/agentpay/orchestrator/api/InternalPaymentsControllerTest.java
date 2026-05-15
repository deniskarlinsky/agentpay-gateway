package com.agentpay.orchestrator.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.saga.PaymentSaga;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = InternalPaymentsController.class)
class InternalPaymentsControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PaymentSaga saga;

  private static final String BODY =
      """
      {
        "case_id": "case-1",
        "agent_id": "agent-1",
        "merchant_id": "merchant-acme",
        "amount": "42.50",
        "currency": "USD",
        "intent_token_jti": "11111111-1111-1111-1111-111111111111",
        "description": "test"
      }
      """;

  @Test
  void newCaseReturnsAcceptedDuplicateFalse() throws Exception {
    when(saga.start(any()))
        .thenReturn(new PaymentSaga.StartResult("case-1", SagaState.COMMITTED, false));

    mockMvc
        .perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.case_id").value("case-1"))
        .andExpect(jsonPath("$.status").value("COMMITTED"))
        .andExpect(jsonPath("$.duplicate").value(false));
  }

  @Test
  void duplicateCaseReturnsAcceptedDuplicateTrue() throws Exception {
    when(saga.start(any()))
        .thenReturn(new PaymentSaga.StartResult("case-1", SagaState.COMMITTED, true));

    mockMvc
        .perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.duplicate").value(true))
        .andExpect(jsonPath("$.status").value("COMMITTED"));
  }
}
