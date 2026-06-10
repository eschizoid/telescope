package io.github.eschizoid.telescope.internal.optics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  /**
   * Lift an element-level {@code Iso<X, Y>} into a {@code List}-level {@code Iso<List<X>,
   * List<Y>>}. Element-wise forward / backward via streaming. Used by the deep mapping factory to
   * derive a container-level conversion from a record-pair conversion automatically.
   */
  static <X, Y> Iso<List<X>, List<Y>> liftList(final Iso<X, Y> element) {
    return of(
      xs -> {
        if (xs == null) return null;
        final var out = new ArrayList<Y>(xs.size());
        for (final var x : xs) out.add(element.to(x));
        return out;
      },
      ys -> {
        if (ys == null) return null;
        final var out = new ArrayList<X>(ys.size());
        for (final var y : ys) out.add(element.from(y));
        return out;
      }
    );
  }

  /**
   * Lift an element-level {@code Iso<X, Y>} into an {@code Optional}-level {@code Iso<Optional<X>,
   * Optional<Y>>}. {@code Optional.empty()} round-trips to {@code Optional.empty()}; a {@code null}
   * Optional reference round-trips to {@code null} (records/beans may legally hold null references
   * and deep mapping treats nulls as pass-through).
   */
  @SuppressWarnings("OptionalAssignedToNull")
  static <X, Y> Iso<Optional<X>, Optional<Y>> liftOptional(final Iso<X, Y> element) {
    return of(ox -> ox == null ? null : ox.map(element::to), oy -> oy == null ? null : oy.map(element::from));
  }

  /**
   * Bridge an element-level {@code Iso<X, Y>} across the asymmetry where one side carries the value
   * in an {@code Optional<X>} and the other side carries it as a possibly-{@code null} {@code Y}.
   * Common cross-paradigm case: a record uses {@code Optional<Address>} for "an address that might
   * not be present"; the corresponding JPA-mapped bean uses a nullable {@code AddressEmbeddable}
   * field.
   *
   * <p>The resulting {@code Iso<Optional<X>, Y>} maps {@code Optional.empty()} ↔ {@code null} and
   * {@code Optional.of(x)} ↔ {@code element.to(x)}; the backward direction wraps {@code y} into
   * {@code Optional.ofNullable(element.from(y))}, so a {@code null} {@code y} or a cycle-severed
   * {@code element.from(y) == null} both collapse to {@code Optional.empty()} rather than throwing.
   * A {@code null} {@code Optional} reference on the source side maps to {@code null} (mirrors the
   * null-pass-through convention of {@link #liftOptional}).
   *
   * <p>For the mirror direction ({@code X} nullable ↔ {@code Optional<Y>}), use {@link #reverse()}
   * on the returned Iso.
   */
  @SuppressWarnings("OptionalAssignedToNull")
  static <X, Y> Iso<Optional<X>, Y> liftOptionalToNullable(final Iso<X, Y> element) {
    return of(
      ox -> ox == null ? null : ox.map(element::to).orElse(null),
      y -> y == null ? Optional.empty() : Optional.ofNullable(element.from(y))
    );
  }

  /**
   * Lift an element-level {@code Iso<X, Y>} into a {@code Set}-level {@code Iso<Set<X>, Set<Y>>}.
   * Output is a {@link LinkedHashSet} preserving forward-pass iteration order. A {@code null} set
   * round-trips to {@code null}.
   *
   * <p><b>Lawfulness caveat.</b> Set semantics are non-injective for element types whose {@code
   * equals} collapses distinct values (e.g. {@code Set<Optional<X>>} where every {@code
   * Optional.empty()} compares equal). Round-trip equality holds under {@code Set.equals} on the
   * resulting set, not on the multiset of source elements. Use {@link #liftList} when element
   * identity must survive.
   */
  static <X, Y> Iso<Set<X>, Set<Y>> liftSet(final Iso<X, Y> element) {
    return of(
      xs -> {
        if (xs == null) return null;
        final var out = new LinkedHashSet<Y>(xs.size());
        for (final var x : xs) out.add(element.to(x));
        return out;
      },
      ys -> {
        if (ys == null) return null;
        final var out = new LinkedHashSet<X>(ys.size());
        for (final var y : ys) out.add(element.from(y));
        return out;
      }
    );
  }

  /**
   * Lift a value-level {@code Iso<X, Y>} into a {@code Map}-values-level {@code Iso<Map<K, X>,
   * Map<K, Y>>}. Keys are preserved; iteration order follows {@link LinkedHashMap}. A {@code null}
   * map round-trips to {@code null}.
   */
  static <K, X, Y> Iso<Map<K, X>, Map<K, Y>> liftMapValues(final Iso<X, Y> value) {
    return of(
      mx -> {
        if (mx == null) return null;
        final var out = new LinkedHashMap<K, Y>(mx.size());
        for (final var entry : mx.entrySet()) out.put(entry.getKey(), value.to(entry.getValue()));
        return out;
      },
      my -> {
        if (my == null) return null;
        final var out = new LinkedHashMap<K, X>(my.size());
        for (final var entry : my.entrySet()) out.put(entry.getKey(), value.from(entry.getValue()));
        return out;
      }
    );
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
