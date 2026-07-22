package io.github.eschizoid.telescope.internal.optics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Read-many: a read-only optic that focuses on zero or more {@code A} values inside an {@code S}.
 * The read half of {@link Traversal}; the dual of {@link Setter}.
 *
 * <p>{@code Fold} is the join point for every read-side optic. {@link Getter}, {@link Traversal},
 * {@link Affine}, {@link Lens}, {@link Prism}, and {@link Iso} all expose folded reads through
 * {@link #getAll(Object)} — a single-focus optic just yields a one- or zero-element stream.
 *
 * <pre>{@code
 * final Fold<Team, User> members = team -> team.users().stream();
 * final var all = members.toList(team);
 * final var admin = members.findFirst(team, User::isAdmin);
 * final var n = members.count(team);
 * }</pre>
 *
 * <p>Two read primitives sit side by side. {@link #getAll(Object)} yields a lazy {@link Stream} —
 * the right tool when the caller wants stream operators or a lazy head ({@code findFirst}). {@link
 * #visitWhile(Object, Predicate)} pushes each focus into a short-circuitable visitor with no
 * intermediate {@link Stream} per hop — the composing optics override it so a deep path is plain
 * nested loops instead of a {@code flatMap} tower. The eager terminals ({@link #toList}, {@link
 * #count}, {@link #forEach}, {@link #any}) ride {@code visitWhile}; both primitives enumerate the
 * same focuses in the same order.
 */
@FunctionalInterface
public interface Fold<S, A> {
  /** Stream every focused {@code A} out of {@code source}, in deterministic visit order. */
  Stream<A> getAll(S source);

  /**
   * Push each focused {@code A} into {@code visitor} in enumeration order, stopping the moment a
   * visit returns {@code false}. Returns {@code true} when every focus was visited, {@code false}
   * when a visit short-circuited. This is the loop-based read primitive the eager terminals ride;
   * composing optics ({@link Traversal#then}, {@link Traversal#filter}, the collection traversals,
   * the single-focus leaves) override it to avoid the per-hop {@link Stream} {@code getAll} builds.
   *
   * <p>The default rides {@link #getAll} for any {@code Fold} that only implements the stream
   * primitive; it enumerates the exact same focuses in the same order.
   */
  default boolean visitWhile(final S source, final Predicate<? super A> visitor) {
    final var it = getAll(source).iterator();
    while (it.hasNext()) if (!visitor.test(it.next())) return false;
    return true;
  }

  /** Push every focused {@code A} into {@code sink} in enumeration order. */
  default void forEach(final S source, final Consumer<? super A> sink) {
    visitWhile(source, a -> {
      sink.accept(a);
      return true;
    });
  }

  /** Materialize all focused values into a list. */
  default List<A> toList(final S source) {
    final var out = new ArrayList<A>();
    forEach(source, out::add);
    return out;
  }

  /** First focused value matching {@code predicate}, or empty if none. */
  default Optional<A> findFirst(final S source, final Predicate<? super A> predicate) {
    final var box = new Object[1];
    final var found = !visitWhile(source, a -> {
      if (!predicate.test(a)) return true;
      box[0] = a;
      return false; // stop at the first match
    });
    @SuppressWarnings("unchecked")
    final var hit = (A) box[0];
    return found ? Optional.ofNullable(hit) : Optional.empty();
  }

  /** Whether any focused value matches {@code predicate}. */
  default boolean any(final S source, final Predicate<? super A> predicate) {
    return !visitWhile(source, a -> !predicate.test(a));
  }

  /** Count of focused values. */
  default long count(final S source) {
    final var c = new long[1];
    forEach(source, a -> c[0]++);
    return c[0];
  }
}
