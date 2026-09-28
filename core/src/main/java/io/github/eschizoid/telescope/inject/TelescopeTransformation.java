package io.github.eschizoid.telescope.inject;

import java.util.function.UnaryOperator;

/**
 * A reusable transformation over a typed path. Generated beans cache the path and the
 * transformation once during construction. Both default factories must be independent of field
 * injection and must return non-null values.
 *
 * @param <S> source model type
 * @param <A> focused value type
 */
public interface TelescopeTransformation<S, A> extends TelescopePath<S, A>, UnaryOperator<S> {
  /** The nullable default and the operation for non-null focused values. */
  Transformation<A> transform();

  /**
   * Returns a copy with the transformation applied to every focus. Missing intermediate objects and
   * empty traversals have no focus; they are not created or populated from the default.
   */
  @Override
  default S apply(final S source) {
    return path().update(source, transform());
  }
}
