package com.github.eschizoid.telescope.internal.optics;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The applicative functor interface for an effect {@code F}. Three operations: lift a pure value
 * into {@code F} ({@link #pure}), transform inside {@code F} ({@link #map}), and combine two {@code
 * F}-shaped values with a binary function ({@link #map2}).
 *
 * <p>{@code map2} is the engine of {@link Traversal#modifyF}: it's what lets us run an effectful
 * function over every focused element and assemble the results back into one effectful structure,
 * with the effect-specific semantics (sequence for {@code CompletableFuture}, short-circuit for
 * {@code Either}, accumulate-errors for {@code Validated}, propagate-empty for {@code Optional})
 * baked in.
 *
 * <p>Internal. Each supported effect ships exactly one {@code Applicative} instance, hidden behind
 * a typed {@link com.github.eschizoid.telescope.Telescope#updateAsync}-style method on {@link
 * com.github.eschizoid.telescope.Telescope}.
 */
public interface Applicative<F extends Kind.Witness> {
  /** Lift a pure value into the effect. */
  <A> Kind<F, A> pure(A value);

  /**
   * Transform the inner value. A primitive (not derived from {@code map2}) because the standard
   * derivation {@code map2(fa, pure(unit), (a, _) -> f.apply(a))} requires a unit value, and the
   * obvious candidate {@code null} breaks for {@link java.util.Optional} (where {@code
   * Optional.ofNullable(null)} is empty).
   */
  <A, B> Kind<F, B> map(Kind<F, A> fa, Function<? super A, ? extends B> f);

  /**
   * Combine two effectful values with a binary function. The operation that determines the effect's
   * semantics — sequential for futures, accumulating for {@code Validated}, short-circuiting for
   * {@code Either}, propagate-empty for {@code Optional}.
   */
  <A, B, C> Kind<F, C> map2(Kind<F, A> fa, Kind<F, B> fb, BiFunction<? super A, ? super B, ? extends C> f);

  /**
   * Optional hook: return {@code true} if {@code fa} is in a state where any subsequent {@link
   * #map2} call will return an equivalent failed state. Lets {@link Traversal#modifyF} skip the
   * per-element function on remaining focused elements once the accumulator has already failed
   * (e.g. {@code Either.Left}, {@code Optional.empty}).
   *
   * <p>Default: {@code false}. Override only for effects with a non-recoverable failure state.
   * {@code Validated} returns false (keep accumulating). {@code CompletableFuture} returns false
   * (parallel — per-element short-circuit doesn't apply).
   */
  default boolean isFailed(final Kind<F, ?> fa) {
    return false;
  }
}
