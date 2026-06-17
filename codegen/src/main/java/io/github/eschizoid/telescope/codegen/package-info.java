/**
 * Annotation processors that emit per-record / per-bean {@code <Type>Path<R>} navigators at compile
 * time, eliminating the per-field reflection cost of {@link
 * io.github.eschizoid.telescope.Telescope}'s {@code .field(...)} on hot paths.
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.codegen.AbstractTelescopeProcessor} — shared skeleton:
 *       round handling, source-file emission, diagnostics, and the templated layout used by every
 *       emitted {@code <X>Path<R>} / {@code <X>Telescope} / {@code <X>Bridge} class.
 *   <li>{@link io.github.eschizoid.telescope.codegen.FocusProcessor} — handles {@link
 *       io.github.eschizoid.telescope.annotations.Focus} on records; emits {@code <Record>Path<R>}
 *       navigators plus a sibling {@code <Record>Telescope} metadata holder (ADR-0006).
 *   <li>{@link io.github.eschizoid.telescope.codegen.BeanFocusProcessor} — handles {@link
 *       io.github.eschizoid.telescope.annotations.BeanFocus} on JavaBean-style POJOs; emits the
 *       same {@code <Bean>Path<R>} navigator shape with getter/setter-backed accessors.
 *   <li>{@link io.github.eschizoid.telescope.codegen.BridgeProcessor} — handles {@link
 *       io.github.eschizoid.telescope.annotations.Bridge} on records or top-level classes (model-
 *       anchored form) and on a third "carrier" class (carrier form, {@code source = X, target =
 *       Y}); emits a sibling {@code <Source>Bridge} / {@code <Carrier>Bridge} class exposing a
 *       {@code Telescope<Source, Target>} constant.
 * </ul>
 *
 * <p>Processors are registered via {@code META-INF/services/javax.annotation.processing.Processor}
 * and discovered by {@code javac} on the annotation-processor path; no module declaration is
 * required.
 */
package io.github.eschizoid.telescope.codegen;
