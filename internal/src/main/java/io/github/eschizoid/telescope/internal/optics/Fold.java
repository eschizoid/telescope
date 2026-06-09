package io.github.eschizoid.telescope.internal.optics;

import java.util.List;
import java.util.Optional;
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
 * <p>The {@link #toList}, {@link #findFirst}, {@link #any}, and {@link #count} defaults are
 * convenience views over {@link #getAll}. There is no {@code then(...)} here; composition happens
 * on the read+write optics.
 */
@FunctionalInterface
public interface Fold<S, A> {
  /** Stream every focused {@code A} out of {@code source}, in deterministic visit order. */
  Stream<A> getAll(S source);

  /** Materialize all focused values into a list. */
  default List<A> toList(final S source) {
    return getAll(source).toList();
  }

  /** First focused value matching {@code predicate}, or empty if none. */
  default Optional<A> findFirst(final S source, final Predicate<? super A> predicate) {
    return getAll(source).filter(predicate).findFirst();
  }

  /** Whether any focused value matches {@code predicate}. */
  default boolean any(final S source, final Predicate<? super A> predicate) {
    return getAll(source).anyMatch(predicate);
  }

  /** Count of focused values. */
  default long count(final S source) {
    return getAll(source).count();
  }
}
