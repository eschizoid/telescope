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
 *   <li>{@link io.github.eschizoid.telescope.mapping.Mapping#toOneWay Mapping.toOneWay(srcAcc,
 *       tgtAcc, fn)} — forward-only typed transform; {@link
 *       io.github.eschizoid.telescope.Telescope#mapper Telescope.mapper(...)} rejects these rows to
 *       keep the partial-Iso shape out of the bidirectional contract; {@link
 *       io.github.eschizoid.telescope.Telescope#mapperForward Telescope.mapperForward(...)} accepts
 *       them.
 *   <li>{@link io.github.eschizoid.telescope.mapping.Mapping#enumTo Mapping.enumTo}, {@link
 *       io.github.eschizoid.telescope.mapping.Mapping#toOrElse Mapping.toOrElse}, {@link
 *       io.github.eschizoid.telescope.mapping.Mapping#toOrElseGet Mapping.toOrElseGet}, {@link
 *       io.github.eschizoid.telescope.mapping.Mapping#zip Mapping.zip}, {@link
 *       io.github.eschizoid.telescope.mapping.Mapping#constant Mapping.constant}, {@link
 *       io.github.eschizoid.telescope.mapping.Mapping#compute Mapping.compute}, {@link
 *       io.github.eschizoid.telescope.mapping.Mapping#when Mapping.when} — the rest of the row
 *       factories. See {@link io.github.eschizoid.telescope.mapping.Mapping} for the full set.
 *   <li>{@link io.github.eschizoid.telescope.mapping.WriteHint#writeBean WriteHint.writeBean(cls,
 *       strategy)} / {@link io.github.eschizoid.telescope.mapping.WriteHint#writeBeans
 *       WriteHint.writeBeans(strategy)} — per-target / default write-strategy hints for bean
 *       reconstruction ({@code SETTERS} / {@code BUILDER} / {@code FIELDS} / {@code CONSTRUCTOR}).
 *   <li>{@link io.github.eschizoid.telescope.mapping.NullHint} — null-handling strategy ({@code
 *       DEFAULT} substitutes JLS defaults via {@code NullDefaults}; otherwise null propagates).
 * </ul>
 *
 * <p>{@link io.github.eschizoid.telescope.mapping.Mapping} and {@link
 * io.github.eschizoid.telescope.mapping.MapStep} are sealed public interfaces — {@code Mapping}
 * permits the row records ({@link io.github.eschizoid.telescope.mapping.SameTypedTo}, {@link
 * io.github.eschizoid.telescope.mapping.TypedTransformTo}, {@link
 * io.github.eschizoid.telescope.mapping.ForwardOnlyTransformTo}, {@link
 * io.github.eschizoid.telescope.mapping.Via}, {@link io.github.eschizoid.telescope.mapping.Drop},
 * {@code TelescopeTo}, {@code FromTelescopeTo}, {@code TelescopeToTelescope}, {@link
 * io.github.eschizoid.telescope.mapping.Constant}, {@link
 * io.github.eschizoid.telescope.mapping.Compute}, {@code Conditional}); {@code MapStep} also
 * permits {@code WriteHint} and {@code NullHint}. Users never name those directly — the static
 * factories above return the sealed interface type.
 *
 * <p>For untyped sources, {@link io.github.eschizoid.telescope.mapping.MapExtractStep} is the
 * sibling sealed interface backing {@link io.github.eschizoid.telescope.Telescope#fromMap} — {@code
 * MapExtractStep.extract(key, accessor, converter)} rows convert a {@code Map<String, Object>} into
 * a typed target. For N-source assembly, {@link io.github.eschizoid.telescope.mapping.MergeStep}
 * backs {@link io.github.eschizoid.telescope.Telescope#merge}.
 */
package io.github.eschizoid.telescope.mapping;
