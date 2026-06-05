package io.github.eschizoid.telescope;

/**
 * A focused value paired with its 0-based position in the traversal order. Produced by {@link
 * Telescope#toListIndexed} and consumed by {@link Telescope#updateIndexed} — useful when a
 * transformation or read depends on where the element sits, not just its value.
 *
 * <p>The index is the flat position in the order {@code getAll} enumerates focused elements, so for
 * a multi-level path ({@code each(...).each(...)}) it counts across the entire flattened focus, not
 * per inner collection.
 *
 * <pre>{@code
 * final Telescope<Team, String> names =
 *     Telescope.of(Team.class).each(Team::members).field(Member::name);
 *
 * // read: pair each focused value with its 0-based flat position
 * final List<Indexed<String>> tagged = names.toListIndexed(team);
 * // [ Indexed[index=0, value=Ann], Indexed[index=1, value=Bob], ... ]
 *
 * // write: updateIndexed passes (position, value) to a BiFunction; same 0-based flat ordering
 * final Team renumbered = names.updateIndexed(team, (i, name) -> i + ": " + name);
 * }</pre>
 */
public record Indexed<A>(int index, A value) {}
