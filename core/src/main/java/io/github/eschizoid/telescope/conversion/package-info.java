/**
 * Bidirectional graph mapping — the {@code Mapper<A, B>} type and the {@code from / to / using}
 * fluent factory exposed via {@link io.github.eschizoid.telescope.Telescope#from(Class)
 * Telescope.from(Class)}.
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.conversion.Mapper} — bidirectional record/bean mapper
 *       produced by the deep recursive factory {@link
 *       io.github.eschizoid.telescope.Telescope#mapper(Class, Class,
 *       io.github.eschizoid.telescope.mapping.MapStep...) Telescope.mapper}. Exposes {@code
 *       forward} / {@code backward}, sparse {@code patch}, {@code asTelescope} for composition into
 *       longer chains, and {@code liftList} / {@code liftSet} / {@code liftOptional} / {@code
 *       liftMapValues} to promote an element-level mapper to a container-shaped one.
 *   <li>{@link io.github.eschizoid.telescope.conversion.From} / {@link
 *       io.github.eschizoid.telescope.conversion.To} — intermediates of the {@code
 *       Telescope.from(Class).to(Class).using(forward, backward)} fluent chain. Internally call
 *       {@link io.github.eschizoid.telescope.Telescope#iso(java.util.function.Function,
 *       java.util.function.Function) Telescope.iso(forward, backward)} so no internal lattice type
 *       appears in their public signatures.
 * </ul>
 *
 * <p>Cross-package callers construct mappers via {@link
 * io.github.eschizoid.telescope.conversion.Mapper#create Mapper.create(forward, backward, ...)} —
 * the public factory that takes {@code Function} pairs and never exposes the internal {@code Iso}
 * type. Same pattern as {@link io.github.eschizoid.telescope.Telescope#iso}.
 */
package io.github.eschizoid.telescope.conversion;
