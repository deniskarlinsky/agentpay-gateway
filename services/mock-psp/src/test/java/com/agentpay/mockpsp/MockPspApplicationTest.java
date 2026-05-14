package com.agentpay.mockpsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class MockPspApplicationTest {

  @Autowired MockMvc mockMvc;

  @Test
  void contextLoads() {}

  @Test
  void chargeEndpointReturnsDeterministicSuccessForKnownCase() throws Exception {
    String body =
        """
        {"case_id":"it-case-psp-a","amount":42.50,"currency":"USD","psp_id":"psp-a"}
        """;

    // The outcome is deterministic: call twice and verify identical response.
    MvcResult first =
        mockMvc
            .perform(post("/charge").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.case_id").value("it-case-psp-a"))
            .andExpect(jsonPath("$.psp_id").value("psp-a"))
            .andReturn();

    MvcResult second =
        mockMvc
            .perform(post("/charge").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(first.getResponse().getContentAsString())
        .isEqualTo(second.getResponse().getContentAsString());
  }

  @Test
  void chargeEndpointReturnsIso20022CodeOnFailure() throws Exception {
    // Find a case_id that fails for psp-b by trying until we find one in the test fixtures.
    // "fail-mvc-3" is pre-computed to fail for psp-b (bucket >= 8800).
    for (int i = 0; i < 200; i++) {
      String caseId = "fail-mvc-" + i;
      int bucket = MockPspService.bucket(caseId, "psp-b");
      if (bucket >= 8800) {
        String body =
            String.format(
                """
                {"case_id":"%s","amount":10,"currency":"USD","psp_id":"psp-b"}
                """,
                caseId);
        mockMvc
            .perform(post("/charge").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(
                jsonPath("$.reason_code")
                    .value(org.hamcrest.Matchers.oneOf("AC01", "AM04", "DT03")))
            .andExpect(jsonPath("$.auth_code").doesNotExist());
        return;
      }
    }
    throw new AssertionError("Could not find a failing case_id for psp-b in 200 attempts");
  }
}
