package com.agentpay.gateway.web;

import com.agentpay.gateway.api.PaymentRequest;
import com.agentpay.gateway.api.PaymentResponse;
import com.agentpay.gateway.payments.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

  private final PaymentService service;

  public PaymentController(PaymentService service) {
    this.service = service;
  }

  @PostMapping("/payments")
  public ResponseEntity<PaymentResponse> submit(
      @AuthenticationPrincipal Jwt token, @RequestBody PaymentRequest request) {
    PaymentResponse body = service.process(token, request);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
  }
}
