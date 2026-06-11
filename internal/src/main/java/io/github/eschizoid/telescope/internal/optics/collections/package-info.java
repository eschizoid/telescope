/**
 * Built-in {@link io.github.eschizoid.telescope.internal.optics.Traversal} factories for the common
 * JDK container shapes. Internal to the library — the DSL's {@code .each(...)} / {@code
 * .filter(...)} chains delegate here.
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.internal.optics.collections.Traversals} — {@code
 *       Traversal} instances for {@link java.util.List}, {@link java.util.Set}, {@link
 *       java.util.Map} values, {@link java.util.Optional}, and arrays, plus filtering and indexing
 *       helpers used by {@code Telescope.each} and friends.
 * </ul>
 *
 * <p>These traversals preserve element order and structural identity where the underlying container
 * does, so {@code update*} round-trips behave predictably on standard JDK collections.
 */
package io.github.eschizoid.telescope.internal.optics.collections;
