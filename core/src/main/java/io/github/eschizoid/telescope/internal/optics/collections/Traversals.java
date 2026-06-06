package io.github.eschizoid.telescope.internal.optics.collections;

import io.github.eschizoid.telescope.internal.optics.Affine;
import io.github.eschizoid.telescope.internal.optics.Traversal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Built-in {@link Traversal}s (and one {@link Affine}) for {@code java.util} container types — the
 * read+write-many optics behind {@code Telescope.each(...)}. Each factory broadcasts a {@code
 * modify} over every element and rebuilds an unmodifiable copy of the container.
 *
 * <pre>{@code
 * final var each = Traversals.<Integer>eachList();
 * final var doubled = each.modify(List.of(1, 2, 3), n -> n * 2); // [2, 4, 6], unmodifiable
 * }</pre>
 *
 * <p>{@link #eachIterable()} is the polymorphic {@code Iterable<E>} variant used by the typed
 * {@code Telescope.each(Accessor)} form when the declared leaf type is {@code Iterable<E>} rather
 * than a specific {@code List}/{@code Set}. It dispatches once on the runtime class to pick the
 * concrete rebuild shape (List → ArrayList, Set → LinkedHashSet, other → ArrayList fallback).
 */
public final class Traversals {

  private Traversals() {}

  /** Traversal over every element of a {@code List}; rebuilds an unmodifiable list. */
  public static <A> Traversal<List<A>, A> eachList() {
    return new Traversal<>() {
      @Override
      public Stream<A> getAll(final List<A> source) {
        return source.stream();
      }

      @Override
      public List<A> modify(final List<A> source, final Function<? super A, ? extends A> f) {
        final var out = new ArrayList<A>(source.size());
        for (final var a : source) out.add(f.apply(a));
        return Collections.unmodifiableList(out);
      }
    };
  }

  /**
   * Traversal over every element of a {@code Set}; rebuilds an unmodifiable {@code LinkedHashSet}.
   */
  public static <A> Traversal<Set<A>, A> eachSet() {
    return new Traversal<>() {
      @Override
      public Stream<A> getAll(final Set<A> source) {
        return source.stream();
      }

      @Override
      public Set<A> modify(final Set<A> source, final Function<? super A, ? extends A> f) {
        final var out = new LinkedHashSet<A>(source.size());
        for (final var a : source) out.add(f.apply(a));
        return Collections.unmodifiableSet(out);
      }
    };
  }

  /**
   * Traversal over every value of a {@code Map}; keys are preserved, rebuilds an unmodifiable map.
   */
  public static <K, V> Traversal<Map<K, V>, V> eachMapValue() {
    return new Traversal<>() {
      @Override
      public Stream<V> getAll(final Map<K, V> source) {
        return source.values().stream();
      }

      @Override
      public Map<K, V> modify(final Map<K, V> source, final Function<? super V, ? extends V> f) {
        final var out = new LinkedHashMap<K, V>(source.size());
        for (final var e : source.entrySet()) out.put(e.getKey(), f.apply(e.getValue()));
        return Collections.unmodifiableMap(out);
      }
    };
  }

  /**
   * Affine over an {@code Optional}'s payload — at-most-one focus. A write on an empty {@code
   * Optional} is a no-op; it does not synthesize a present value (that's why it's an Affine, not a
   * Prism).
   */
  public static <A> Affine<Optional<A>, A> eachOptional() {
    return Affine.of(source -> source, (source, a) -> source.isPresent() ? Optional.of(a) : source);
  }

  /**
   * A {@link Traversal} over any {@link Iterable} container ({@code List}, {@code Set}, any custom
   * {@code Iterable}). Streams via {@link Iterable#iterator()} and rebuilds via the concrete
   * container's typed {@code Traversals.each*} primitive when the actual class is known (List →
   * {@code ArrayList}, Set → {@code LinkedHashSet}); other Iterables rebuild as unmodifiable lists.
   *
   * <p>Used by the typed {@code Telescope.each(Accessor<A, ? extends Iterable<E>>)} form on
   * Telescope, which declares the leaf as Iterable to accept either List or Set without locking the
   * caller into one concrete shape. Arrays are NOT supported — wrap as a List or Set if your model
   * uses arrays.
   */
  @SuppressWarnings({ "unchecked", "cast" })
  public static <C extends Iterable<E>, E> Traversal<C, E> eachIterable() {
    return new Traversal<>() {
      @Override
      public Stream<E> getAll(final C source) {
        if (source == null) return Stream.empty();
        return java.util.stream.StreamSupport.stream(source.spliterator(), false);
      }

      @Override
      public C modify(final C source, final Function<? super E, ? extends E> f) {
        if (source == null) return null;
        if (source instanceof List<?>) {
          final var out = new ArrayList<E>();
          for (final var e : source) out.add(f.apply(e));
          return (C) Collections.unmodifiableList(out);
        }
        if (source instanceof Set<?>) {
          final var out = new LinkedHashSet<E>();
          for (final var e : source) out.add(f.apply(e));
          return (C) Collections.unmodifiableSet(out);
        }
        // Other Iterable shapes — rebuild as an immutable List (best-effort; the typed leaf
        // .each(Accessor<A, List<X>>) or .each(Accessor<A, Set<X>>) preserves the original shape).
        final var out = new ArrayList<E>();
        for (final var e : source) out.add(f.apply(e));
        return (C) Collections.unmodifiableList(out);
      }
    };
  }
}
