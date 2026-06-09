/**
 * Row-builder DSL for the deep recursive mapping factory. Exported as part of the library's public
 * API; consumed by {@link io.github.eschizoid.telescope.Telescope#map(Class, Class, MapStep...)
 * Telescope.map} and {@link io.github.eschizoid.telescope.Telescope#mapper(Class, Class,
 * MapStep...) Telescope.mapper} as varargs of typed rows.
 *
 * <h2>The two row families</h2>
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.mapping.Mapping} — one field correspondence between a
 *       source/target type pair. Built with the static factories:
 *       <ul>
 *         <li>{@link io.github.eschizoid.telescope.mapping.Mapping#to(
 *             io.github.eschizoid.telescope.Telescope.Accessor,
 *             io.github.eschizoid.telescope.Telescope.Accessor) Mapping.to(src, tgt)} — plain
 *             rename, same type on both sides.
 *         <li>{@link io.github.eschizoid.telescope.mapping.Mapping#to(
 *             io.github.eschizoid.telescope.Telescope.Accessor,
 *             io.github.eschizoid.telescope.Telescope.Accessor, java.util.function.Function,
 *             java.util.function.Function) Mapping.to(src, tgt, fwd, bwd)} — typed transform; the
 *             two function args carry the leaf-type change.
 *         <li>{@link io.github.eschizoid.telescope.mapping.Mapping#via(
 *             io.github.eschizoid.telescope.Telescope.Accessor,
 *             io.github.eschizoid.telescope.Telescope.Accessor,
 *             io.github.eschizoid.telescope.conversion.Mapper) Mapping.via(src, tgt, mapper)} —
 *             drop in a pre-built nested {@link io.github.eschizoid.telescope.conversion.Mapper};
 *             auto-lifts element-level mappers through {@code List} / {@code Set} / {@code
 *             Optional} / {@code Map} values when the accessor's field shape matches the mapper's
 *             element class.
 *       </ul>
 *   <li>{@link io.github.eschizoid.telescope.mapping.WriteHint} — per-target write-strategy
 *       override. Built with the static factories:
 *       <ul>
 *         <li>{@link io.github.eschizoid.telescope.mapping.WriteHint#writeBean(Class,
 *             io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy) writeBean(Class,
 *             STRATEGY)} — pin one specific target class to a {@code BUILDER} / {@code SETTERS} /
 *             {@code FIELDS} / {@code CONSTRUCTOR} strategy, overriding the auto-detected choice
 *             from {@code Beans.autoWriter}.
 *         <li>{@link io.github.eschizoid.telescope.mapping.WriteHint#writeBeans(
 *             io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy) writeBeans(STRATEGY)}
 *             — default applied to every bean target the recursion touches that lacks a more
 *             specific {@code writeBean(Class, ...)} override. Collapses the "pin SETTERS across
 *             every JPA entity" enumeration from N rows to one.
 *       </ul>
 * </ul>
 *
 * <p>Both families implement {@link io.github.eschizoid.telescope.mapping.MapStep}, the marker
 * interface that {@link io.github.eschizoid.telescope.Telescope#map(Class, Class, MapStep...)} and
 * {@link io.github.eschizoid.telescope.Telescope#mapper(Class, Class, MapStep...)} accept as
 * varargs. Rows from either family can be mixed in any order at the call site.
 *
 * <h2>How rows are keyed</h2>
 *
 * <p>Each {@code Mapping} row carries its source and target classes implicitly via the method
 * references that built it (recovered through {@code SerializedLambda}). The deep recursion uses
 * {@code (sourceClass, targetClass)} as the lookup key — a row written here applies wherever the
 * recursion lands on that pair, top-level or N levels deep. Same-name same-type pairs that need no
 * row are inferred automatically; only declare a row for a genuine difference (rename, typed
 * transform, or nested-mapper composition).
 *
 * <p>The concrete record implementations of {@link io.github.eschizoid.telescope.mapping.Mapping}
 * ({@code SameTypedTo}, {@code TypedTransformTo}, {@code Via}) and the {@link
 * io.github.eschizoid.telescope.mapping.MappingInternals} accessor interface are package-private
 * and not part of the public API — users construct rows via the static factories above.
 */
package io.github.eschizoid.telescope.mapping;
