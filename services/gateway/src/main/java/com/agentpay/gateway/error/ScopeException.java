package com.agentpay.gateway.error;

public class ScopeException extends RuntimeException {
  private final String errorCode;

  public ScopeException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }
}
