package io.github.eschizoid.telescope.internal.optics.collections;

import io.github.eschizoid.telescope.internal.optics.Affine;
import io.github.eschizoid.telescope.internal.optics.Traversal;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
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
 * <p>{@link #eachContainer()} is the runtime-dispatching fallback used when the container shape
 * isn't known statically — it inspects the value and delegates to the right branch.
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
   * A {@link Traversal} that handles any container type ({@code List}, {@code Set}, {@code Map}
   * values, {@code Optional}, or array) by runtime dispatch. Used internally by {@code
   * Telescope.each()} when the container shape isn't known statically.
   */
  @SuppressWarnings({ "unchecked", "cast" })
  public static <C, E> Traversal<C, E> eachContainer() {
    return new Traversal<>() {
      @Override
      public Stream<E> getAll(final C source) {
        return (Stream<E>) containerStream(source);
      }

      @Override
      public C modify(final C source, final Function<? super E, ? extends E> f) {
        return (C) containerUpdate(source, (Function<Object, Object>) f);
      }
    };
  }

  private static Stream<?> containerStream(final Object container) {
    switch (container) {
      case null -> {
        return Stream.empty();
      }
      case List<?> l -> {
        return l.stream();
      }
      case Set<?> s -> {
        return s.stream();
      }
      case Map<?, ?> m -> {
        return m.values().stream();
      }
      case Optional<?> o -> {
        return o.stream();
      }
      default -> {
      }
    }
    if (container.getClass().isArray()) return arrayStream(container);
    throw new IllegalArgumentException(
      "each() requires List/Set/Map/Optional/array, got " + container.getClass().getName()
    );
  }

  @SuppressWarnings("unchecked")
  private static Object containerUpdate(final Object container, final Function<Object, Object> fn) {
    switch (container) {
      case null -> {
        return null;
      }
      case List<?> l -> {
        final var out = new ArrayList<>(l.size());
        for (final var e : l) out.add(fn.apply(e));
        return Collections.unmodifiableList(out);
      }
      case Set<?> s -> {
        final var out = new LinkedHashSet<>(s.size());
        for (final var e : s) out.add(fn.apply(e));
        return Collections.unmodifiableSet(out);
      }
      case Map<?, ?> m -> {
        final var out = new LinkedHashMap<>(m.size());
        for (final var e : ((Map<Object, Object>) m).entrySet()) {
          out.put(e.getKey(), fn.apply(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
      }
      case Optional<?> o -> {
        return o.isPresent() ? Optional.of(fn.apply(o.get())) : o;
      }
      default -> {
      }
    }
    if (container.getClass().isArray()) return arrayUpdate(container, fn);
    throw new IllegalArgumentException(
      "each() requires List/Set/Map/Optional/array, got " + container.getClass().getName()
    );
  }

  // Reflection-based array handling that works uniformly for primitive arrays (int[], long[],
  // double[], ...) and Object arrays. `java.lang.reflect.Array.get` boxes primitive values into
  // their wrapper types; `Array.set` auto-unboxes them on the way back into a fresh array of the
  // original component type.
  private static Stream<?> arrayStream(final Object array) {
    final var len = Array.getLength(array);
    return IntStream.range(0, len).mapToObj(i -> Array.get(array, i));
  }

  private static Object arrayUpdate(final Object array, final Function<Object, Object> fn) {
    final var len = Array.getLength(array);
    final var componentType = array.getClass().getComponentType();
    final var out = Array.newInstance(componentType, len);
    for (var i = 0; i < len; i++) Array.set(out, i, fn.apply(Array.get(array, i)));
    return out;
  }
}
