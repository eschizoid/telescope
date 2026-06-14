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
import java.util.stream.StreamSupport;

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
 * concrete rebuild shape (List → ArrayList, Set → LinkedHashSet). Other {@code Iterable} subtypes
 * (Queue, Deque, custom iterables) are rejected at {@code modify} time — see {@link
 * #eachIterable()} for the rationale.
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
   * A {@link Traversal} over an {@link Iterable} container — rebuilds {@link List} sources as an
   * unmodifiable {@code ArrayList}-backed list, {@link Set} sources as an unmodifiable {@link
   * LinkedHashSet}-backed set.
   *
   * <p><b>Supported shapes are List and Set only.</b> Used by the typed {@code
   * Telescope.each(Accessor<A, ? extends Iterable<E>>)} form on Telescope so a getter declared as
   * {@code Iterable<E>} accepts either concrete kind without locking the call site to one. Any
   * other {@code Iterable} subtype ({@link java.util.Queue Queue}, {@link java.util.Deque Deque}, a
   * custom {@code FooIterable}) is rejected at {@code modify(...)} time with a {@link
   * ClassCastException}-equivalent {@link IllegalArgumentException} — the rebuild can only preserve
   * container identity (i.e. honor the {@code C} type parameter without a downstream cast failure)
   * for {@code List} and {@code Set}. If your model uses {@code Queue}/{@code Deque} /custom
   * iterables, declare the getter as {@code List<E>} or {@code Set<E>} and explicitly convert at
   * the boundary instead.
   *
   * <p>Arrays are NOT supported — wrap as a List or Set if your model uses arrays.
   */
  @SuppressWarnings({ "unchecked", "cast" })
  public static <C extends Iterable<E>, E> Traversal<C, E> eachIterable() {
    return new Traversal<>() {
      @Override
      public Stream<E> getAll(final C source) {
        if (source == null) return Stream.empty();
        return StreamSupport.stream(source.spliterator(), false);
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
        // No safe rebuild for other Iterable shapes — the (C) cast would succeed on a List but
        // throw ClassCastException downstream when callers store it into a field typed as e.g.
        // Queue<E>. Refuse upfront with a clear message instead of fabricating an unsafe cast.
        throw new IllegalArgumentException(
          "Traversals.eachIterable() supports only List and Set sources; got " +
            source.getClass().getName() +
            ". Declare your record component / bean property as List<E> or Set<E>, " +
            "not the raw Iterable subtype, so the rebuild can preserve container identity."
        );
      }
    };
  }
}
