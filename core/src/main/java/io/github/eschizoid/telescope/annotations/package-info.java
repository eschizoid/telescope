/**
 * Compile-time markers consumed by the telescope annotation processors. Exported as part of the
 * library's public API. The entry points ({@code @Focus}, {@code @BeanFocus}, {@code @Bridge},
 * {@code @FromMap}) go on a declaration, as does {@code @UncheckedMapping}, which suppresses
 * verification rather than generating anything; the per-field modifiers ({@code @Default},
 * {@code @Rename}, {@code @Transform}, {@code @Constant}, {@code @Compute}, {@code @ViaMapper}) are
 * only meaningful inside a {@code @Bridge} attribute and declare no applicable declaration target,
 * so javac rejects them anywhere else.
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.annotations.Focus} — applied to a record. The {@code
 *       :codegen} processor emits a sibling {@code <Record>Telescope<R>} navigator plus a {@code
 *       <Record>FieldOptics} metadata holder, eliminating the per-field reflection cost of {@code
 *       .field(...)}.
 *   <li>{@link io.github.eschizoid.telescope.annotations.BeanFocus} — the POJO counterpart of
 *       {@code @Focus}. Drives generation of {@code <Bean>Telescope<R>} navigators for
 *       getter/setter beans (including Lombok {@code @Data} / {@code @Value} / {@code @Builder}
 *       classes via the {@code :lombok} processor).
 *   <li>{@link io.github.eschizoid.telescope.annotations.Bridge} — applied to a record or class
 *       (model-anchored) or a carrier class (carrier form, {@code source = X, target = Y}) to
 *       generate a reflection-free, compile-checked bridge; the codegen counterpart of the deep
 *       recursive {@link io.github.eschizoid.telescope.Telescope#map(Class, Class,
 *       io.github.eschizoid.telescope.mapping.MapStep...)} factory. {@code lenient = true} opts out
 *       of the strict bijection check for the small-DTO → large-entity pattern.
 *   <li>{@link io.github.eschizoid.telescope.annotations.Rename} / {@link
 *       io.github.eschizoid.telescope.annotations.Transform} / {@link
 *       io.github.eschizoid.telescope.annotations.Constant} / {@link
 *       io.github.eschizoid.telescope.annotations.Compute} / {@link
 *       io.github.eschizoid.telescope.annotations.Default} / {@link
 *       io.github.eschizoid.telescope.annotations.ViaMapper} — per-field modifiers consumed by
 *       {@code @Bridge}, mirroring the runtime row factories on {@code Mapping}.
 *   <li>{@link io.github.eschizoid.telescope.annotations.Bridges} — javac's {@link
 *       java.lang.annotation.Repeatable} container for multiple {@code @Bridge}s on the same type.
 *   <li>{@link io.github.eschizoid.telescope.annotations.WriteStrategy} — override for the POJO
 *       construction strategy when emitting the target's rebuild block.
 * </ul>
 *
 * <p>The annotations themselves carry no runtime behavior — they are read by the {@code
 * AbstractProcessor}s shipped in the {@code telescope-codegen} and {@code telescope-lombok}
 * modules.
 */
package io.github.eschizoid.telescope.annotations;
