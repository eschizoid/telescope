package io.github.eschizoid.telescope.internal.optics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Read-one: a read-only optic that focuses on exactly one {@code A} inside an {@code S}. The
 * read-only counterpart of {@link Lens} — it has {@link #get} but no write half.
 *
 * <p>It's a single-element {@link Fold}: {@link #getAll} yields a stream of exactly one element.
 *
 * <pre>{@code
 * final Getter<User, String> name = user -> user.name();
 * final var n = name.get(user); // exactly one A
 * }</pre>
 *
 * <p>Falls out of the lattice as the read-only specialization that {@link Lens} extends. Rarely
 * built directly — most field navigation produces a full {@link Lens}. Two Getters compose into a
 * Getter via {@link #then(Getter)}; the write-side composition lives on {@link Lens} and the other
 * read+write optics.
 */
@FunctionalInterface
public interface Getter<S, A> extends Fold<S, A> {
  /** Read the single focused {@code A} out of {@code source}. */
  A get(S source);

  /** Fold view: the one focused value as a singleton stream. */
  @Override
  default Stream<A> getAll(final S source) {
    return Stream.of(get(source));
  }

  /** Fold view: visit the single focused value. */
  @Override
  default boolean visitWhile(final S source, final Predicate<? super A> visitor) {
    return visitor.test(get(source));
  }

  /**
   * Compose with another {@code Getter} to read deeper. {@code this.then(next).get(s)} is
   * equivalent to {@code next.get(this.get(s))} — the canonical Getter-composition shape from the
   * lattice. Used by {@code ForwardMapper#then} in the {@code conversion} package to keep
   * forward-only composition lattice-routed instead of an ad-hoc {@code Function} closure.
   */
  default <B> Getter<S, B> then(final Getter<A, B> next) {
    return s -> next.get(get(s));
  }

  /**
   * Lift an element-level {@code Getter<X, Y>} into a {@code List}-level {@code Getter<List<X>,
   * List<Y>>}. Element-wise read via the element Getter. {@code null} lists round-trip to {@code
   * null} (mirrors the convention of {@link Iso#liftList}). Forward-only counterpart of {@link
   * Iso#liftList} — used by {@code ForwardMapper#liftList} in the {@code conversion} package.
   */
  static <X, Y> Getter<List<X>, List<Y>> liftList(final Getter<X, Y> element) {
    return xs -> {
      if (xs == null) return null;
      final var out = new ArrayList<Y>(xs.size());
      for (final var x : xs) out.add(element.get(x));
      return out;
    };
  }

  /**
   * Lift an element-level {@code Getter<X, Y>} into a {@code Set}-level {@code Getter<Set<X>,
   * Set<Y>>}. Element-wise read into a fresh {@link LinkedHashSet} (preserves forward-pass
   * iteration order). {@code null} sets round-trip to {@code null}.
   *
   * <p><b>Collapsing-equals caveat.</b> When the element {@code Getter} maps distinct {@code X}
   * values to {@code Y} values that compare {@code equal} (e.g. a getter producing the same key for
   * multiple distinct sources), the output {@code Set<Y>} silently drops duplicates — the element
   * count of the lifted output can be strictly less than the input. Same trade-off as {@link
   * Iso#liftSet}; use {@link #liftList} when element identity must survive.
   */
  static <X, Y> Getter<Set<X>, Set<Y>> liftSet(final Getter<X, Y> element) {
    return xs -> {
      if (xs == null) return null;
      final var out = new LinkedHashSet<Y>(xs.size());
      for (final var x : xs) out.add(element.get(x));
      return out;
    };
  }

  /**
   * Lift an element-level {@code Getter<X, Y>} into an {@code Optional}-level {@code
   * Getter<Optional<X>, Optional<Y>>}. {@code Optional.empty()} maps to {@code Optional.empty()}; a
   * {@code null} reference (records/beans may legally hold null Optionals) maps to {@code null}.
   */
  @SuppressWarnings("OptionalAssignedToNull")
  static <X, Y> Getter<Optional<X>, Optional<Y>> liftOptional(final Getter<X, Y> element) {
    return ox -> ox == null ? null : ox.map(element::get);
  }

  /**
   * Lift an element-level {@code Getter<X, Y>} into a {@code Map}-values-level {@code Getter<Map<K,
   * X>, Map<K, Y>>}. Keys are preserved verbatim; only values flow through the element Getter.
   * {@code null} maps round-trip to {@code null}.
   */
  static <K, X, Y> Getter<Map<K, X>, Map<K, Y>> liftMapValues(final Getter<X, Y> element) {
    return xs -> {
      if (xs == null) return null;
      final var out = new LinkedHashMap<K, Y>(xs.size());
      for (final var e : xs.entrySet()) out.put(e.getKey(), element.get(e.getValue()));
      return out;
    };
  }
}
