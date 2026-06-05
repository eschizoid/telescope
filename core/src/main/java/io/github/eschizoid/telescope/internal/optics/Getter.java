package io.github.eschizoid.telescope.internal.optics;

import java.util.stream.Stream;

/**
 * Read-one: a read-only optic that focuses on exactly one {@code A} inside an {@code S}. The
 * read-only counterpart of {@link Lens} — it has {@link #get} but no write half.
 *
 * <p>It's a single-element {@link Fold}: {@link #getAll} yields a stream of exactly one element.
 *
 * <pre>{@code
 * final Getter<User, String> name = user -> user.name();
 * final var n = name.get(user); // exactly one A
 * }</pre>
 *
 * <p>Falls out of the lattice as the read-only specialization that {@link Lens} extends. Rarely
 * built directly — most field navigation produces a full {@link Lens}. There is no {@code
 * then(...)} here; composition happens on the read+write optics.
 */
@FunctionalInterface
public interface Getter<S, A> extends Fold<S, A> {
  /** Read the single focused {@code A} out of {@code source}. */
  A get(S source);

  /** Fold view: the one focused value as a singleton stream. */
  @Override
  default Stream<A> getAll(final S source) {
    return Stream.of(get(source));
  }
}
