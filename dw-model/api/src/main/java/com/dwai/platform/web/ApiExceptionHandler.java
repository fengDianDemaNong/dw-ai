package com.dwai.platform.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
    HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
    String msg = e.getReason() != null ? e.getReason() : status.getReasonPhrase();
    return ResponseEntity.status(status).body(Map.of("error", msg, "status", status.value()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "status", 400));
  }
}
