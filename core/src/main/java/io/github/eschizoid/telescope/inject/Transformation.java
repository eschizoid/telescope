package io.github.eschizoid.telescope.inject;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A nullable default and an operation for non-null focused values. A null input returns the default
 * directly, without invoking the operation. A null result from the operation remains null.
 *
 * <p>The default is one shared instance: every call with a null focus writes that same instance
 * into its result, so all such results share it, even on a single thread. Mutating a mutable
 * default afterwards changes every result that received it, so use an immutable default. The
 * operation is shared the same way, so a transformation held by a singleton bean needs a
 * thread-safe operation.
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
