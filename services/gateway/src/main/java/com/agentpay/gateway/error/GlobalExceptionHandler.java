package com.agentpay.gateway.error;

import com.agentpay.gateway.api.ErrorResponse;
import com.agentpay.gateway.orchestrator.OrchestratorUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse(ex.errorCode(), ex.getMessage()));
  }

  @ExceptionHandler(ScopeException.class)
  public ResponseEntity<ErrorResponse> handleScope(ScopeException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorResponse(ex.errorCode(), ex.getMessage()));
  }

  @ExceptionHandler(OrchestratorUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleOrchestratorDown(OrchestratorUnavailableException ex) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ErrorResponse(ex.errorCode(), ex.getMessage()));
  }
}
