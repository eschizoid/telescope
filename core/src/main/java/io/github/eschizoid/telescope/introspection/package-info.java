/**
 * Optic introspection — the queryable structure behind {@code explain()} and {@code trace(input)}.
 *
 * <p>A {@code Telescope}, {@code Mapper}, or {@code ForwardMapper} resolves what it does (field
 * pairings, or a navigation path) and, historically, discarded that structure. This package is the
 * public shape it is now surfaced through: an ordered {@link
 * io.github.eschizoid.telescope.introspection.OpticNode} trail wrapped in an {@link
 * io.github.eschizoid.telescope.introspection.OpticReport}. The trail is derived from the same
 * decisions the optic uses to build itself, so it cannot drift from what the optic actually does.
 *
 * <ul>
 *   <li>{@code explain()} returns the static {@link
 *       io.github.eschizoid.telescope.introspection.OpticReport} — structure only, no input needed.
 *   <li>{@code trace(input)} enriches the same trail with each node's actual value, expanding
 *       many-focus steps into per-element subtrees.
 * </ul>
 *
 * <p>Data first: assert on {@link io.github.eschizoid.telescope.introspection.OpticReport#mapped()}
 * / {@code transformations()} / {@code skipped()}; the {@code toString()} render is a view.
 */
package io.github.eschizoid.telescope.introspection;
