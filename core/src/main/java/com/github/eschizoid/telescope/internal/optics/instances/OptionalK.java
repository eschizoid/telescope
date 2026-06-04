package com.github.eschizoid.telescope.internal.optics.instances;

import com.github.eschizoid.telescope.internal.optics.Applicative;
import com.github.eschizoid.telescope.internal.optics.Kind;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * {@link com.github.eschizoid.telescope.internal.optics.Kind.Witness} + {@link
 * com.github.eschizoid.telescope.internal.optics.Applicative} instance for {@link
 * java.util.Optional} — the effect lifted by {@link
 * com.github.eschizoid.telescope.internal.optics.Traversal#modifyF}. Semantics: any empty input
 * propagates empty. {@code pure} uses {@code ofNullable}, {@code map2} short-circuits to empty if
 * either side is empty, and {@code isFailed} returns {@code true} for an empty — so {@code modifyF}
 * stops at the first empty element.
 *
 * <p>Used by {@link com.github.eschizoid.telescope.Telescope#updateOptional}. Users never type this
 * class — they see {@code Optional<S>} on the way out.
 */
public final class OptionalK implements Kind.Witness {

  private OptionalK() {}

  private record Holder<A>(Optional<A> value) implements Kind<OptionalK, A> {}

  /** Wrap an {@code Optional<A>} into the {@link Kind} carrier. */
  public static <A> Kind<OptionalK, A> box(final Optional<A> opt) {
    return new Holder<>(opt);
  }

  /** Unwrap the {@link Kind} carrier back to an {@code Optional<A>}. */
  public static <A> Optional<A> unbox(final Kind<OptionalK, A> k) {
    return ((Holder<A>) k).value();
  }

  private static final Applicative<OptionalK> APPLICATIVE = new Applicative<>() {
    @Override
    public <A> Kind<OptionalK, A> pure(final A value) {
      return box(Optional.ofNullable(value));
    }

    @Override
    public <A, B> Kind<OptionalK, B> map(final Kind<OptionalK, A> fa, final Function<? super A, ? extends B> f) {
      return box(unbox(fa).map(f));
    }

    @Override
    public <A, B, C> Kind<OptionalK, C> map2(
      final Kind<OptionalK, A> fa,
      final Kind<OptionalK, B> fb,
      final BiFunction<? super A, ? super B, ? extends C> f
    ) {
      final var oa = unbox(fa);
      final var ob = unbox(fb);
      if (oa.isEmpty() || ob.isEmpty()) return box(Optional.empty());
      return box(Optional.of(f.apply(oa.get(), ob.get())));
    }

    @Override
    public boolean isFailed(final Kind<OptionalK, ?> fa) {
      return ((Holder<?>) fa).value().isEmpty();
    }
  };

  /** The shared, stateless applicative instance for this effect. */
  public static Applicative<OptionalK> applicative() {
    return APPLICATIVE;
  }
}
