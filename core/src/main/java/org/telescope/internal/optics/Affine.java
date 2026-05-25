package org.telescope.internal.optics;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Read+write at-most-one: focuses on at-most-one {@code A} inside an {@code S}, with the ability to
 * write a new {@code A} back into a pre-existing {@code S} — but no ability to synthesize an {@code
 * S} from scratch (that's the half {@link Prism} adds).
 *
 * <p>Affine is the common supertype of {@link Lens} (always finds an {@code A}) and {@link Prism}
 * (sometimes finds an {@code A}, and can reconstruct). Mixed compositions like {@code
 * Lens.then(Prism)} land here, because the Lens half can't reconstruct and the Prism half might not
 * match — so the result is neither a Lens nor a Prism.
 *
 * <pre>{@code
 * final var head = Affine.<List<Integer>, Integer>of(
 *     xs -> xs.isEmpty() ? Optional.empty() : Optional.of(xs.get(0)),
 *     (xs, n) -> { final var c = new ArrayList<>(xs); c.set(0, n); return c; });
 * final var first = head.getOption(nums);            // Optional<Integer>
 * final var bumped = head.modify(nums, n -> n + 1);  // miss → source unchanged
 * }</pre>
 *
 * <p>In practice you rarely build an Affine by hand. It shows up as the type of an intermediate
 * step in a longer path.
 *
 * <h2>Composition (Affine as outer)</h2>
 *
 * <ul>
 *   <li>{@code Affine.then(Lens|Prism|Iso|Affine)} → {@link Affine}
 *   <li>{@code Affine.then(Traversal)} → {@link Traversal} (inherited)
 * </ul>
 */
public interface Affine<S, A> extends Traversal<S, A> {
  /** Try to read the focused {@code A} — present when it exists, empty otherwise. */
  Optional<A> getOption(S source);

  @Override
  default Stream<A> getAll(final S source) {
    return getOption(source).stream();
  }

  /** Build an Affine from a partial getter and an {@code (S, A) -> S} setter. */
  static <S, A> Affine<S, A> of(
    final Function<? super S, Optional<A>> getOption,
    final BiFunction<? super S, ? super A, ? extends S> set
  ) {
    return new Affine<>() {
      @Override
      public Optional<A> getOption(final S source) {
        return getOption.apply(source);
      }

      @Override
      public S modify(final S source, final Function<? super A, ? extends A> f) {
        final var current = getOption.apply(source);
        if (current.isEmpty()) return source;
        return set.apply(source, f.apply(current.get()));
      }
    };
  }

  /**
   * Single-focus override of {@link Traversal#modifyF}: read the at-most-one {@code A}, lift it
   * through {@code fn}, and write the result back through {@link Traversal#modify}. Inherited by
   * {@link Lens}, {@link Prism}, and {@link Iso} — they're all single-focus, no list
   * materialization needed.
   */
  @Override
  default <F extends Kind.Witness> Kind<F, S> modifyF(
    final Applicative<F> applicative,
    final S source,
    final Function<? super A, ? extends Kind<F, A>> fn
  ) {
    final var maybe = getOption(source);
    if (maybe.isEmpty()) return applicative.pure(source);
    final Kind<F, A> fa = fn.apply(maybe.get());
    return applicative.map(fa, newA -> modify(source, ignored -> newA));
  }

  /** {@code Affine . Affine = Affine} */
  default <B> Affine<S, B> then(final Affine<A, B> next) {
    final var self = this;
    return new Affine<>() {
      @Override
      public Optional<B> getOption(final S source) {
        return self.getOption(source).flatMap(next::getOption);
      }

      @Override
      public S modify(final S source, final Function<? super B, ? extends B> f) {
        return self.modify(source, a -> next.modify(a, f));
      }
    };
  }
}
