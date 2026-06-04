package com.github.eschizoid.telescope.internal.optics.instances;

import com.github.eschizoid.telescope.Validated;
import com.github.eschizoid.telescope.internal.optics.Applicative;
import com.github.eschizoid.telescope.internal.optics.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * {@link com.github.eschizoid.telescope.internal.optics.Kind.Witness} + {@link
 * com.github.eschizoid.telescope.internal.optics.Applicative} instance for {@link Validated} fixed
 * to a specific error type {@code E} — the effect lifted by {@link
 * com.github.eschizoid.telescope.internal.optics.Traversal#modifyF}. Semantics: accumulate errors
 * across both arguments of {@code map2} (concatenate the two {@code Invalid} error lists). {@code
 * pure} is a {@code Valid}.
 *
 * <p>This accumulating {@code map2} is the whole reason {@code Validated} exists separately from
 * {@link com.github.eschizoid.telescope.Either} — when one branch fails, we still want to evaluate
 * the other and report every problem, not stop at the first. Accordingly there is no {@code
 * isFailed} override (it stays {@code false}), so {@code modifyF} processes every focused element
 * rather than short-circuiting.
 *
 * <p>Used by {@link com.github.eschizoid.telescope.Telescope#updateValidated}.
 */
public final class ValidatedK<E> implements Kind.Witness {

  private ValidatedK() {}

  private record Holder<E, A>(Validated<E, A> value) implements Kind<ValidatedK<E>, A> {}

  /** Wrap a {@code Validated<E, A>} into the {@link Kind} carrier. */
  public static <E, A> Kind<ValidatedK<E>, A> box(final Validated<E, A> v) {
    return new Holder<>(v);
  }

  /** Unwrap the {@link Kind} carrier back to a {@code Validated<E, A>}. */
  public static <E, A> Validated<E, A> unbox(final Kind<ValidatedK<E>, A> k) {
    return ((Holder<E, A>) k).value();
  }

  /** One applicative instance per error type. Stateless; safe to cache. */
  public static <E> Applicative<ValidatedK<E>> forError() {
    return new Applicative<>() {
      @Override
      public <A> Kind<ValidatedK<E>, A> pure(final A value) {
        return box(Validated.valid(value));
      }

      @Override
      public <A, B> Kind<ValidatedK<E>, B> map(
        final Kind<ValidatedK<E>, A> fa,
        final Function<? super A, ? extends B> f
      ) {
        return box(unbox(fa).map(f));
      }

      @Override
      public <A, B, C> Kind<ValidatedK<E>, C> map2(
        final Kind<ValidatedK<E>, A> fa,
        final Kind<ValidatedK<E>, B> fb,
        final BiFunction<? super A, ? super B, ? extends C> f
      ) {
        final var va = unbox(fa);
        final var vb = unbox(fb);
        if (
          va instanceof Validated.Invalid<E, A>(List<E> errors1) &&
          vb instanceof Validated.Invalid<E, B>(List<E> errors2)
        ) {
          final var combined = new ArrayList<E>(errors1.size() + errors2.size());
          combined.addAll(errors1);
          combined.addAll(errors2);
          return box(Validated.invalid(combined));
        }
        if (va instanceof Validated.Invalid<E, A>(List<E> errors)) return box(Validated.invalid(errors));
        if (vb instanceof Validated.Invalid<E, B>(List<E> errors)) return box(Validated.invalid(errors));
        final var a = ((Validated.Valid<E, A>) va).value();
        final var b = ((Validated.Valid<E, B>) vb).value();
        return box(Validated.valid(f.apply(a, b)));
      }
    };
  }
}
