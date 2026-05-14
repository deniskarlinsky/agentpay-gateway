package com.agentpay.gateway.web;

import com.nimbusds.jose.jwk.JWKSet;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WellKnownJwksController {

  private final JWKSet jwkSet;

  public WellKnownJwksController(JWKSet jwkSet) {
    this.jwkSet = jwkSet;
  }

  @GetMapping("/.well-known/jwks.json")
  public Map<String, Object> jwks() {
    return jwkSet.toJSONObject();
  }
}
