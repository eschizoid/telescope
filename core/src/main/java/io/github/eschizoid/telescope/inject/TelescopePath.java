package io.github.eschizoid.telescope.inject;

import io.github.eschizoid.telescope.Telescope;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** An injectable, reusable typed path into a model. */
public interface TelescopePath<S, A> {
  /** The underlying Telescope value, built once per bean. */
  Telescope<S, A> path();

  /** Read the focused value. */
  default A read(final S source) {
    return path().read(source);
  }

  /** Find the first focus, or return empty when it is absent or null. */
  default Optional<A> find(final S source) {
    return path().find(source);
  }

  /** Read every focus, in traversal order. */
  default List<A> toList(final S source) {
    return path().toList(source);
  }

  /** Return a copy with the focused value replaced. */
  default S set(final S source, final A value) {
    return path().set(source, value);
  }

  /** Return a copy with the focused value transformed. */
  default S update(final S source, final Function<A, A> update) {
    return path().update(source, update);
  }
}
