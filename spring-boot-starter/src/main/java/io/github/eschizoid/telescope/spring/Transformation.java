package io.github.eschizoid.telescope.spring;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A nullable default and an operation for non-null focused values. A null input returns the default
 * directly, without invoking the operation. A null result from the operation remains null.
 *
 * <p>The default is reused by reference. Use an immutable default and a thread-safe operation when
 * sharing this transformation in a singleton bean.
 *
 * @param defaultValue replacement for a null focused value; may be null
 * @param operation operation applied exactly once to each non-null focused value
 * @param <A> focused value type
 */
public record Transformation<A>(A defaultValue, UnaryOperator<A> operation) implements UnaryOperator<A> {
  /** Creates a transformation; the operation must not be null. */
  public Transformation {
    Objects.requireNonNull(operation, "operation must not be null");
  }

  /** Applies the null default or the operation, according to the input value. */
  @Override
  public A apply(final A value) {
    return value == null ? defaultValue : operation.apply(value);
  }
}
