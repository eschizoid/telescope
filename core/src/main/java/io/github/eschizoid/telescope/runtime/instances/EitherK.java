package io.github.eschizoid.telescope.runtime.instances;

import io.github.eschizoid.telescope.effects.Either;
import io.github.eschizoid.telescope.internal.optics.Applicative;
import io.github.eschizoid.telescope.internal.optics.Kind;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * {@link io.github.eschizoid.telescope.internal.optics.Kind.Witness} + {@link
 * io.github.eschizoid.telescope.internal.optics.Applicative} instance for {@link Either} fixed to a
 * specific left type {@code L} — the effect lifted by {@link
 * io.github.eschizoid.telescope.internal.optics.Traversal#modifyF}. Semantics: short-circuit on the
 * first {@code Left}. {@code pure} is a {@code Right}, {@code map2} returns the first {@code Left}
 * it sees, and {@code isFailed} returns {@code true} for a {@code Left} — so {@code modifyF} stops
 * invoking the per-element function once a failure is reached.
 *
 * <p>Because Java has no higher-kinded types, the left type {@code L} is captured in the witness
 * instance itself rather than as a {@code Kind} parameter — call {@link #forLeft} once per left
 * type and reuse the returned {@code EitherK<L>}.
 *
 * <p>Used by {@link io.github.eschizoid.telescope.Telescope#updateEither}.
 */
public final class EitherK<L> implements Kind.Witness {

  private EitherK() {}

  private record Holder<L, A>(Either<L, A> value) implements Kind<EitherK<L>, A> {}

  /** Wrap an {@code Either<L, A>} into the {@link Kind} carrier. */
  public static <L, A> Kind<EitherK<L>, A> box(final Either<L, A> e) {
    return new Holder<>(e);
  }

  /** Unwrap the {@link Kind} carrier back to an {@code Either<L, A>}. */
  public static <L, A> Either<L, A> unbox(final Kind<EitherK<L>, A> k) {
    return ((Holder<L, A>) k).value();
  }

  /** One applicative instance per left type. Stateless; safe to cache. */
  public static <L> Applicative<EitherK<L>> forLeft() {
    return new Applicative<>() {
      @Override
      public <A> Kind<EitherK<L>, A> pure(final A value) {
        return box(Either.right(value));
      }

      @Override
      public <A, B> Kind<EitherK<L>, B> map(final Kind<EitherK<L>, A> fa, final Function<? super A, ? extends B> f) {
        return box(unbox(fa).map(f));
      }

      @Override
      public <A, B, C> Kind<EitherK<L>, C> map2(
        final Kind<EitherK<L>, A> fa,
        final Kind<EitherK<L>, B> fb,
        final BiFunction<? super A, ? super B, ? extends C> f
      ) {
        // Sealed-aware nested dispatch through `fold` — short-circuits on the first Left and
        // collapses the explicit double-instanceof chain (which left dead branches that JaCoCo
        // flagged as partials / defensive throw lines).
        return box(unbox(fa).flatMap(a -> unbox(fb).map(b -> f.apply(a, b))));
      }

      @Override
      public boolean isFailed(final Kind<EitherK<L>, ?> fa) {
        return ((Holder<L, ?>) fa).value() instanceof Either.Left<?, ?>;
      }
    };
  }
}
