package com.agentpay.gateway.orchestrator;

public class OrchestratorUnavailableException extends RuntimeException {

  public OrchestratorUnavailableException(String message) {
    super(message);
  }

  public String errorCode() {
    return "ORCHESTRATOR_UNAVAILABLE";
  }
}
