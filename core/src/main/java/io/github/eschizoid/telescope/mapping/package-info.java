/**
 * Row-builder DSL consumed as varargs by the deep recursive mapping factories {@link
 * io.github.eschizoid.telescope.Telescope#map(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...) Telescope.map} and {@link
 * io.github.eschizoid.telescope.Telescope#mapper(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...) Telescope.mapper}. Each row contributes a
 * leaf-level correspondence; the engine ({@code DeepMap}) composes them via the internal optic
 * lattice while reading only the public components on each row — so no internal type appears on any
 * row's public signature.
 *
 * <h2>Rows</h2>
 *
 * <ul>
 *   <li>{@link
 *       io.github.eschizoid.telescope.mapping.Mapping#to(io.github.eschizoid.telescope.Telescope.Accessor,
 *       io.github.eschizoid.telescope.Telescope.Accessor) Mapping.to(srcAcc, tgtAcc)} — same-typed
 *       correspondence (record component {@code id} on both sides). The leaf-level Iso is identity.
 *   <li>{@link
 *       io.github.eschizoid.telescope.mapping.Mapping#to(io.github.eschizoid.telescope.Telescope.Accessor,
 *       io.github.eschizoid.telescope.Telescope.Accessor, java.util.function.Function,
 *       java.util.function.Function) Mapping.to(srcAcc, tgtAcc, fwd, bwd)} — typed transform
 *       between differently-typed fields (e.g. {@code String ↔ UUID}).
 *   <li>{@link
 *       io.github.eschizoid.telescope.mapping.Mapping#via(io.github.eschizoid.telescope.Telescope.Accessor,
 *       io.github.eschizoid.telescope.Telescope.Accessor,
 *       io.github.eschizoid.telescope.conversion.Mapper) Mapping.via(srcAcc, tgtAcc, nested)} —
 *       nested mapper for a sub-graph (e.g. {@code Address ↔ AddressDto}). Auto-lifts through
 *       {@code List} / {@code Set} / {@code Optional} / {@code Map} values when the accessors point
 *       at a container of the nested mapper's element type.
 *   <li>{@link
 *       io.github.eschizoid.telescope.mapping.Mapping#drop(io.github.eschizoid.telescope.Telescope.Accessor)
 *       Mapping.drop(srcAcc)} — declare a source field intentionally NOT mapped to the target. Lets
 *       the strict deep-map factory accept the pair without requiring a same-name target.
 *   <li>{@link io.github.eschizoid.telescope.mapping.WriteHint#writeBean WriteHint.writeBean(cls,
 *       strategy)} / {@link io.github.eschizoid.telescope.mapping.WriteHint#writeBeans
 *       WriteHint.writeBeans(strategy)} — per-target / default write-strategy hints for bean
 *       reconstruction ({@code SETTERS} / {@code BUILDER} / {@code FIELDS} / {@code CONSTRUCTOR}).
 * </ul>
 *
 * <p>{@link io.github.eschizoid.telescope.mapping.Mapping} and {@link
 * io.github.eschizoid.telescope.mapping.MapStep} are sealed public interfaces; their {@code
 * permits} clause lists the concrete row records ({@link
 * io.github.eschizoid.telescope.mapping.SameTypedTo}, {@link
 * io.github.eschizoid.telescope.mapping.TypedTransformTo}, {@link
 * io.github.eschizoid.telescope.mapping.Via}, {@link io.github.eschizoid.telescope.mapping.Drop},
 * {@link io.github.eschizoid.telescope.mapping.WriteHint}). Users never name those directly — the
 * static factories above return the sealed interface type.
 */
package io.github.eschizoid.telescope.mapping;
