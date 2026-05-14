package com.agentpay.gateway.web;

import com.agentpay.gateway.api.AgentCard;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentCardController {

  private static final AgentCard CARD =
      new AgentCard(
          "AgentPay Gateway",
          "Agent-native payment gateway. Authenticates AI agents and authorizes scoped payments.",
          "http://localhost:8080/",
          "0.1.0",
          "0.3.0",
          new AgentCard.Capabilities(false),
          List.of("application/json"),
          List.of("application/json"),
          List.of(
              new AgentCard.Skill(
                  "request_intent_token",
                  "Request intent token",
                  "Obtain a scoped, short-lived JWT to authorize one payment.",
                  List.of("payments", "authorization")),
              new AgentCard.Skill(
                  "submit_payment",
                  "Submit payment",
                  "Submit a payment authorized by a prior intent token.",
                  List.of("payments"))));

  @GetMapping("/.well-known/agent.json")
  public AgentCard card() {
    return CARD;
  }
}
