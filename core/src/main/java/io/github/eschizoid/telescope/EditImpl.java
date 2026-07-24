package io.github.eschizoid.telescope;

import java.util.function.Function;

/**
 * The single {@link Edit} implementation. Package-private so users construct edits only through
 * {@link Edit#over(Telescope, Function)} and never see this type at the call site.
 */
record EditImpl<S, X>(Telescope<S, X> path, Function<X, X> fn) implements Edit<S> {
  @Override
  public S apply(final S s) {
    return path.update(s, fn);
  }
}
