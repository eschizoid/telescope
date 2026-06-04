/**
 * Annotation processors that emit per-record / per-bean {@code <Type>Focus} navigators at compile
 * time, eliminating the per-field reflection cost of {@link
 * com.github.eschizoid.telescope.Telescope}'s {@code .field(...)} on hot paths.
 *
 * <ul>
 *   <li>{@link com.github.eschizoid.telescope.codegen.AbstractTelescopeProcessor} — shared
 *       skeleton: round handling, source-file emission, diagnostics, and the templated layout used
 *       by every emitted {@code *Focus} / bridge class.
 *   <li>{@link com.github.eschizoid.telescope.codegen.FocusProcessor} — handles {@link
 *       com.github.eschizoid.telescope.annotations.Focus} on records; emits {@code <Record>Focus}
 *       with one optic constant per component.
 *   <li>{@link com.github.eschizoid.telescope.codegen.BeanFocusProcessor} — handles {@link
 *       com.github.eschizoid.telescope.annotations.BeanFocus} on JavaBean-style POJOs; emits
 *       getter/setter-backed {@code <Bean>Focus} navigators.
 *   <li>{@link com.github.eschizoid.telescope.codegen.BridgeProcessor} — handles {@link
 *       com.github.eschizoid.telescope.annotations.Bridge} on records; emits a sibling {@code
 *       <Record>Bridge} class exposing a {@code Telescope<Pojo, Record>} constant.
 * </ul>
 *
 * <p>Processors are registered via {@code META-INF/services/javax.annotation.processing.Processor}
 * and discovered by {@code javac} on the annotation-processor path; no module declaration is
 * required.
 */
package com.github.eschizoid.telescope.codegen;
