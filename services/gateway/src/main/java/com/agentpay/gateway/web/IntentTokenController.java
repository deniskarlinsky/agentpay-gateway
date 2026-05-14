package com.agentpay.gateway.web;

import com.agentpay.gateway.api.IntentTokenRequest;
import com.agentpay.gateway.api.IntentTokenResponse;
import com.agentpay.gateway.token.IntentTokenService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntentTokenController {

  private final IntentTokenService service;

  public IntentTokenController(IntentTokenService service) {
    this.service = service;
  }

  @PostMapping("/intent-tokens")
  public IntentTokenResponse issue(@RequestBody IntentTokenRequest request) {
    return service.issue(request);
  }
}
