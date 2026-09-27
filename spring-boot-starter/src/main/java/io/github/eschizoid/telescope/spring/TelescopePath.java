package io.github.eschizoid.telescope.spring;

import io.github.eschizoid.telescope.Telescope;
import java.util.function.UnaryOperator;

/** An injectable, reusable typed path into a model. */
public interface TelescopePath<S, A> {
  /** The underlying Telescope value, built once per bean. */
  Telescope<S, A> path();

  /** Read the focused value. */
  default A read(final S source) {
    return path().read(source);
  }

  /** Return a copy with the focused value replaced. */
  default S set(final S source, final A value) {
    return path().set(source, value);
  }

  /** Return a copy with the focused value transformed. */
  default S update(final S source, final UnaryOperator<A> update) {
    return path().update(source, update);
  }
}
