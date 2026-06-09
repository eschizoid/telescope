package io.github.eschizoid.telescope.demo.spring.api;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link InvalidOrderException} (carrying a {@link
 * io.github.eschizoid.telescope.Validated.Invalid}) to a 400 response with the accumulated error
 * list as JSON. The advice never touches Validated directly — the controller throws an exception
 * wrapper, the advice downcasts via the carried payload to surface the typed errors.
 */
@RestControllerAdvice
public class ValidatedExceptionAdvice {

  public record ErrorResponse(String message, List<LineItemValidationError> errors) {}

  @ExceptionHandler(InvalidOrderException.class)
  public ResponseEntity<ErrorResponse> handle(final InvalidOrderException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(ex.getMessage(), ex.errors()));
  }
}
