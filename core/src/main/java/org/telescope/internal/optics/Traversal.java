package org.telescope.internal.optics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Read+write many: focuses on zero-or-more {@code A}s inside an {@code S}, with read access via
 * {@link #getAll(Object)} and write access via {@link Setter#modify(Object,
 * java.util.function.Function)}. Traversal is the widest write-capable optic in the lattice — every
 * {@link Affine}, {@link Lens}, {@link Prism}, and {@link Iso} IS-A Traversal, but once you widen
 * into a Traversal you can't recover the narrower types.
 *
 * <p>Used internally for collection broadcast ({@link
 * org.telescope.internal.optics.collections.Traversals#eachList} and friends), and as the storage
 * type inside {@link org.telescope.Telescope} — the DSL accepts whatever optic each navigation step
 * produces and immediately collapses it through {@code .then(...)}, so the wrapper only ever holds
 * a Traversal.
 *
 * <pre>{@code
 * final Traversal<List<Integer>, Integer> each =
 *     org.telescope.internal.optics.collections.Traversals.eachList();
 * final var all = each.toList(nums);                // read every A (via Fold)
 * final var doubled = each.modify(nums, n -> n * 2); // write every A (via Setter)
 * final var evensTimesTen = each.filter(n -> n % 2 == 0).modify(nums, n -> n * 10);
 * }</pre>
 *
 * <h2>Composition (Traversal as outer)</h2>
 *
 * <p>{@code Traversal.then(anything)} → {@link Traversal}. Widening is one-way: there is no path
 * back to a single-focus optic.
 *
 * <h2>filter</h2>
 *
 * <p>{@link #filter(Predicate)} restricts both reads and writes to elements matching the predicate.
 * Non-matching elements pass through {@code modify} unchanged.
 */
public interface Traversal<S, A> extends Fold<S, A>, Setter<S, A> {
  /** Narrow this traversal to focus only the {@code A}s matching {@code predicate}. */
  default Traversal<S, A> filter(final Predicate<? super A> predicate) {
    final var self = this;
    return new Traversal<>() {
      @Override
      public Stream<A> getAll(final S source) {
        return self.getAll(source).filter(predicate);
      }

      @Override
      public S modify(final S source, final Function<? super A, ? extends A> f) {
        return self.modify(source, a -> predicate.test(a) ? f.apply(a) : a);
      }
    };
  }

  /**
   * Lift this traversal over an effectful function. For each focused {@code A}, apply {@code fn} to
   * get a {@code Kind<F, A>}, combine them all into a single {@code Kind<F, S>} using the
   * applicative's {@link Applicative#map2 map2}. The effect-specific semantics (sequencing,
   * short-circuiting, error accumulation, empty-propagation) live entirely in the {@link
   * Applicative} instance — this method is effect-agnostic.
   *
   * <p>Default implementation handles the many-focus case via an iterator trick: collect all
   * focused values, sequence the effectful results into one effectful list, then map the result
   * back through {@link #modify} pulling new values from the list in order. Relies on the invariant
   * that {@code modify} visits focused elements in the same order {@link #getAll} enumerates them —
   * true for every implementor in this package.
   *
   * <p>Single-focus optics override this for a more direct path that skips the list allocation.
   */
  default <F extends Kind.Witness> Kind<F, S> modifyF(
    final Applicative<F> applicative,
    final S source,
    final Function<? super A, ? extends Kind<F, A>> fn
  ) {
    final List<A> allAs = getAll(source).toList();
    if (allAs.isEmpty()) return applicative.pure(source);

    Kind<F, List<A>> sequenced = applicative.pure(new ArrayList<>());
    for (final var a : allAs) {
      // True short-circuit: skip fn invocation on remaining elements once the accumulator
      // is in a non-recoverable failed state (Either.Left, Optional.empty). For applicatives
      // that accumulate (Validated) or evaluate in parallel (CompletableFuture), isFailed
      // returns false and every element is processed.
      if (applicative.isFailed(sequenced)) break;
      final Kind<F, A> fa = fn.apply(a);
      sequenced = applicative.map2(sequenced, fa, (list, newA) -> {
        final var copy = new ArrayList<A>(list.size() + 1);
        copy.addAll(list);
        copy.add(newA);
        return copy;
      });
    }

    return applicative.map(sequenced, newValues -> {
      final var iter = newValues.iterator();
      return modify(source, ignored -> iter.next());
    });
  }

  /** {@code Traversal . anything = Traversal} */
  default <B> Traversal<S, B> then(final Traversal<A, B> next) {
    final var self = this;
    return new Traversal<>() {
      @Override
      public Stream<B> getAll(final S source) {
        return self.getAll(source).flatMap(next::getAll);
      }

      @Override
      public S modify(final S source, final Function<? super B, ? extends B> f) {
        return self.modify(source, a -> next.modify(a, f));
      }
    };
  }
}
