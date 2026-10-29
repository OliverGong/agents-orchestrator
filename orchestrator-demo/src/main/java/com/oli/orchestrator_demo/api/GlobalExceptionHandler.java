/**
 * @ClassName GlobalExceptionHandler
 * @Description Maps workflow failures to meaningful HTTP responses instead of
 *              a bare 500: malformed input -> 400, unparseable model output
 *              -> 502, unreachable/timing-out model backend -> 503, unknown
 *              route -> 404, anything else -> 500. Every mapped failure is
 *              logged, so nothing is silently swallowed.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.api;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.core.JacksonException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  record ApiErrorResponse(Instant timestamp, int status, String error, String message) {
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiErrorResponse> malformedBody(HttpMessageNotReadableException ex) {
    log.warn("Malformed request body: {}", ex.getMessage());
    return respond(HttpStatus.BAD_REQUEST, "Malformed request body", ex.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ApiErrorResponse> invalidInput(IllegalArgumentException ex) {
    log.warn("Invalid request: {}", ex.getMessage());
    return respond(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
  }

  /** The model answered, but its output could not be parsed into the expected structure. */
  @ExceptionHandler(JacksonException.class)
  ResponseEntity<ApiErrorResponse> unparseableModelOutput(JacksonException ex) {
    log.warn("Model output unparseable: {}", ex.getMessage());
    return respond(HttpStatus.BAD_GATEWAY, "Model returned unparseable output", ex.getMessage());
  }

  /** The model backend is unreachable, rejects the call or times out (connect/read timeout). */
  @ExceptionHandler({RestClientException.class, ResourceAccessException.class})
  ResponseEntity<ApiErrorResponse> backendUnavailable(Exception ex) {
    log.warn("Model backend unavailable: {}", ex.getMessage());
    return respond(HttpStatus.SERVICE_UNAVAILABLE, "Model backend unavailable", ex.getMessage());
  }

  /** Unknown route (e.g. /actuator on a build without it) instead of a misleading 500. */
  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiErrorResponse> unknownPath(NoResourceFoundException ex) {
    log.warn("Unknown resource: {}", ex.getMessage());
    return respond(HttpStatus.NOT_FOUND, "Not found", ex.getMessage());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> unexpected(Exception ex) {
    log.error("Unexpected workflow failure", ex);
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", ex.getMessage());
  }

  private static ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String error, String message) {
    return ResponseEntity.status(status)
        .body(new ApiErrorResponse(Instant.now(), status.value(), error, message));
  }
}
