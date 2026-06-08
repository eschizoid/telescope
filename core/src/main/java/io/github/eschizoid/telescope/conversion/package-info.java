/**
 * Type-conversion surface. Exported as part of the library's public API; complements the navigation
 * + update DSL in {@code io.github.eschizoid.telescope} and the row-builder DSL in {@code
 * io.github.eschizoid.telescope.mapping}.
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.conversion.Mapper} — bidirectional mapper produced by
 *       {@link io.github.eschizoid.telescope.Telescope#mapper(Class, Class,
 *       io.github.eschizoid.telescope.mapping.MapStep...) Telescope.mapper}. Exposes {@code
 *       forward(a)} / {@code backward(b)} / {@code read(a)} for one-shot conversion, {@code
 *       patch(base, partial)} for sparse-overlay updates, {@code asTelescope()} for {@code
 *       .then(...)} composition into a longer typed path, and {@code liftList()} / {@code
 *       liftSet()} / {@code liftOptional()} / {@code liftMapValues()} for promoting an
 *       element-level mapper to a container-level mapper without going through {@code via(...)}.
 *   <li>{@link io.github.eschizoid.telescope.conversion.From} / {@link
 *       io.github.eschizoid.telescope.conversion.To} — package-internal seams used by {@link
 *       io.github.eschizoid.telescope.Telescope#from(Class)} / {@code .to(Class)} / {@code
 *       .using(forward, backward)} to construct a hand-written {@code Iso}-backed {@code
 *       Telescope}. Not intended for direct construction by user code; treat as module-internal.
 * </ul>
 *
 * <p>The {@code Mapper} type is the {@code Telescope<A, B>} sibling for cases that benefit from the
 * sparse-overlay {@code patch} terminal or from explicit container promotion via {@code lift*}.
 * Behaviour-wise it carries the same underlying {@code Iso<A, B>} as {@code Telescope.map(A.class,
 * B.class, ...)}; the difference is the surface — {@code Mapper} foregrounds the "convert this
 * whole graph" use case while {@code Telescope<A, B>} foregrounds the "compose into a longer path"
 * use case.
 */
package io.github.eschizoid.telescope.conversion;
