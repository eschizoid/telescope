package io.github.eschizoid.telescope.demo.spring.api;

import io.github.eschizoid.telescope.Validated;
import java.util.List;

/**
 * Thin RuntimeException wrapper that carries a {@link Validated.Invalid} payload across the Spring
 * controller boundary so a {@code @ControllerAdvice} can translate it into a 400 response with the
 * full accumulated error list. The advice downcasts via {@code instanceof} to recover the typed
 * {@link LineItemValidationError} entries.
 */
public final class InvalidOrderException extends RuntimeException {

  private final transient Validated.Invalid<LineItemValidationError, ?> invalid;

  public InvalidOrderException(final Validated.Invalid<LineItemValidationError, ?> invalid) {
    super("order failed validation with " + invalid.errors().size() + " error(s)");
    this.invalid = invalid;
  }

  public List<LineItemValidationError> errors() {
    return invalid.errors();
  }
}
