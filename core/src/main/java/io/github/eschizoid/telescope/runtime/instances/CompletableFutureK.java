package io.github.eschizoid.telescope.runtime.instances;

import io.github.eschizoid.telescope.internal.optics.Applicative;
import io.github.eschizoid.telescope.internal.optics.Kind;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * {@link io.github.eschizoid.telescope.internal.optics.Kind.Witness} + {@link
 * io.github.eschizoid.telescope.internal.optics.Applicative} instance for {@link CompletableFuture}
 * — the effect lifted by {@link io.github.eschizoid.telescope.internal.optics.Traversal#modifyF}.
 * Semantics: futures execute in parallel (whatever schedule they already have); {@code pure} is a
 * completed future, {@code map2} waits for both via {@code thenCombine} and combines them, and
 * failure of either propagates.
 *
 * <p>{@code isFailed} is left at its default {@code false}: a future's outcome isn't known
 * synchronously, so there's no per-element short-circuit — every focused element is processed.
 *
 * <p>Used by {@link io.github.eschizoid.telescope.Telescope#updateAsync}. The result {@code
 * CompletableFuture<S>} completes when every focused element's future has completed.
 */
public final class CompletableFutureK implements Kind.Witness {

  private CompletableFutureK() {}

  private record Holder<A>(CompletableFuture<A> value) implements Kind<CompletableFutureK, A> {}

  /** Wrap a {@code CompletableFuture<A>} into the {@link Kind} carrier. */
  public static <A> Kind<CompletableFutureK, A> box(final CompletableFuture<A> cf) {
    return new Holder<>(cf);
  }

  /** Unwrap the {@link Kind} carrier back to a {@code CompletableFuture<A>}. */
  public static <A> CompletableFuture<A> unbox(final Kind<CompletableFutureK, A> k) {
    return ((Holder<A>) k).value();
  }

  private static final Applicative<CompletableFutureK> APPLICATIVE = new Applicative<>() {
    @Override
    public <A> Kind<CompletableFutureK, A> pure(final A value) {
      return box(CompletableFuture.completedFuture(value));
    }

    @Override
    public <A, B> Kind<CompletableFutureK, B> map(
      final Kind<CompletableFutureK, A> fa,
      final Function<? super A, ? extends B> f
    ) {
      return box(unbox(fa).thenApply(f));
    }

    @Override
    public <A, B, C> Kind<CompletableFutureK, C> map2(
      final Kind<CompletableFutureK, A> fa,
      final Kind<CompletableFutureK, B> fb,
      final BiFunction<? super A, ? super B, ? extends C> f
    ) {
      final var a = unbox(fa);
      final var b = unbox(fb);
      return box(a.thenCombine(b, f));
    }
  };

  /** The shared, stateless applicative instance for this effect. */
  public static Applicative<CompletableFutureK> applicative() {
    return APPLICATIVE;
  }
}
