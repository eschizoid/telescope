package org.telescope.internal.optics;

import java.util.function.Function;

/**
 * Write-many: a write-only optic that can modify every focused {@code A} inside an {@code S},
 * returning a new {@code S}. The write half of {@link Traversal}; the dual of {@link Fold}.
 *
 * <p>{@link #modify} is the primitive; {@link #set} is the constant-function special case.
 *
 * <pre>{@code
 * final Setter<List<Integer>, Integer> each = ...; // every element
 * final var doubled = each.modify(nums, n -> n * 2);
 * final var zeroed = each.set(nums, 0);
 * }</pre>
 *
 * <p>Falls out of the lattice as the write-only specialization that {@link Traversal} extends.
 * Rarely built directly. There is no {@code then(...)} here; composition happens on the read+write
 * optics.
 */
@FunctionalInterface
public interface Setter<S, A> {
  /** Apply {@code f} to every focused {@code A} in {@code source}, returning a new {@code S}. */
  S modify(S source, Function<? super A, ? extends A> f);

  /** Overwrite every focused {@code A} with {@code value} — {@code modify} with a constant. */
  default S set(final S source, final A value) {
    return modify(source, ignored -> value);
  }
}
