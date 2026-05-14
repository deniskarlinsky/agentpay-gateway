package com.agentpay.mockpsp;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChargeController {

  private final MockPspService service;

  public ChargeController(MockPspService service) {
    this.service = service;
  }

  @PostMapping("/charge")
  public ChargeResponse charge(@RequestBody ChargeRequest request) {
    return service.charge(request);
  }
}
