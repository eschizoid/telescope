package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.util.function.Function;

/**
 * Intermediate of {@link From#to(Class)} — call {@link #using(Function, Function)} to supply both
 * directions of the conversion and materialize the resulting {@code Telescope<A, B>}.
 */
public final class To<A, B> {

  public To() {}

  /**
   * Supply both directions of the conversion. {@code forward} converts {@code A → B}; {@code
   * backward} must satisfy the iso laws ({@code from(to(a)).equals(a)} and {@code
   * to(from(b)).equals(b)} for the components involved). The resulting {@code Telescope<A, B>}
   * composes into longer paths via {@link Telescope#then}. See {@link Telescope#from(Class)} for a
   * worked example.
   */
  public Telescope<A, B> using(
    final Function<? super A, ? extends B> forward,
    final Function<? super B, ? extends A> backward
  ) {
    return Telescope.wrap(Iso.of(forward, backward));
  }
}
