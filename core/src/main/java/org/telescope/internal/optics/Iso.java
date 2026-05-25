package org.telescope.internal.optics;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Reversible exactly-one ↔ exactly-one: every {@code A} maps to a unique {@code B} and back without
 * loss. Iso is the most specific optic in the lattice — it extends both {@link Lens} (the
 * conversion is total in both directions) and {@link Prism} (it can rebuild an {@code A} from any
 * {@code B}).
 *
 * <p>Use it for lossless type conversions: entity ↔ DTO, Celsius ↔ Fahrenheit, wrapper ↔ primitive.
 * If your conversion can throw or drop information, reach for a {@link Prism} (partial) or a {@link
 * Lens} (one-way) instead.
 *
 * <pre>{@code
 * final var celsius = Iso.<Double, Double>of(c -> c * 9 / 5 + 32, f -> (f - 32) * 5 / 9);
 * final var f = celsius.to(20.0);        // 68.0
 * final var c = celsius.from(68.0);      // 20.0
 * final var bumped = celsius.modify(20.0, f2 -> f2 + 1); // edit in the B view
 * }</pre>
 *
 * <h2>Composition (Iso as outer)</h2>
 *
 * <ul>
 *   <li>{@code Iso.then(Iso)} → {@link Iso}, {@code Iso.then(Lens)} → {@link Lens}, {@code
 *       Iso.then(Prism)} → {@link Prism}
 *   <li>{@code Iso.then(Affine)} → {@link Affine}, {@code Iso.then(Traversal)} → {@link Traversal}
 *       (inherited)
 * </ul>
 *
 * <p>Because an Iso IS-A Lens and IS-A Prism, the {@code Iso.then(Lens)} / {@code Iso.then(Prism)}
 * cases are resolved by the explicit overrides {@link #then(Lens)} and {@link #then(Prism)} below.
 *
 * <h2>Laws</h2>
 *
 * <ul>
 *   <li>forward round-trip: {@code iso.from(iso.to(a)).equals(a)}
 *   <li>backward round-trip: {@code iso.to(iso.from(b)).equals(b)}
 * </ul>
 */
public interface Iso<A, B> extends Lens<A, B>, Prism<A, B> {
  /** Forward conversion {@code A -> B}. */
  B to(final A source);

  /** Backward conversion {@code B -> A}, inverse of {@link #to}. */
  A from(final B value);

  @Override
  default B get(final A source) {
    return to(source);
  }

  @Override
  default A set(final A source, final B value) {
    return from(value);
  }

  @Override
  default A reverseGet(final B value) {
    return from(value);
  }

  @Override
  default Optional<B> getOption(final A source) {
    return Optional.of(to(source));
  }

  @Override
  default A modify(final A source, final Function<? super B, ? extends B> f) {
    return from(f.apply(to(source)));
  }

  @Override
  default Stream<B> getAll(final A source) {
    return Stream.of(to(source));
  }

  /** Build an Iso from two inverse functions (must satisfy both round-trip laws). */
  static <A, B> Iso<A, B> of(
    final Function<? super A, ? extends B> forward,
    final Function<? super B, ? extends A> backward
  ) {
    return new Iso<>() {
      @Override
      public B to(final A source) {
        return forward.apply(source);
      }

      @Override
      public A from(final B value) {
        return backward.apply(value);
      }
    };
  }

  /** Identity Iso — useful as the root for path composition. */
  static <X> Iso<X, X> identity() {
    return of(x -> x, x -> x);
  }

  /** Swap directions — {@code to} and {@code from} trade places. */
  default Iso<B, A> reverse() {
    final var self = this;
    return Iso.of(self::from, self::to);
  }

  /** {@code Iso . Iso = Iso} */
  default <C> Iso<A, C> then(final Iso<B, C> next) {
    final var self = this;
    return Iso.of(a -> next.to(self.to(a)), c -> self.from(next.from(c)));
  }

  /** {@code Iso . Lens = Lens} — diamond resolution */
  @Override
  default <C> Lens<A, C> then(final Lens<B, C> next) {
    final var self = this;
    return Lens.of(a -> next.get(self.to(a)), (a, c) -> self.from(next.set(self.to(a), c)));
  }

  /** {@code Iso . Prism = Prism} — diamond resolution */
  @Override
  default <C> Prism<A, C> then(final Prism<B, C> next) {
    final var self = this;
    return Prism.of(a -> next.getOption(self.to(a)), c -> self.from(next.reverseGet(c)));
  }
}
