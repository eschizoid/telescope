/**
 * Per-effect {@code Kind}/{@code Applicative} witness pairs that power the four effectful {@code
 * update*} methods on {@link com.github.eschizoid.telescope.Telescope}. Internal to the library —
 * boxing into the witness happens at the DSL boundary and is invisible to user code.
 *
 * <ul>
 *   <li>{@link com.github.eschizoid.telescope.internal.optics.instances.OptionalK} — {@link
 *       java.util.Optional} witness; backs {@code updateOptional}.
 *   <li>{@link com.github.eschizoid.telescope.internal.optics.instances.EitherK} — {@link
 *       com.github.eschizoid.telescope.Either} witness; backs {@code updateEither} and
 *       short-circuits on the first {@code Left}.
 *   <li>{@link com.github.eschizoid.telescope.internal.optics.instances.ValidatedK} — {@link
 *       com.github.eschizoid.telescope.Validated} witness; backs {@code updateValidated} and
 *       accumulates errors across every focused element.
 *   <li>{@link com.github.eschizoid.telescope.internal.optics.instances.CompletableFutureK} —
 *       {@link java.util.concurrent.CompletableFuture} witness; backs {@code updateAsync}.
 * </ul>
 *
 * <p>Each instance pairs a {@code Kind} encoding (lightweight HKT emulation) with an {@code
 * Applicative} so the same {@link com.github.eschizoid.telescope.internal.optics.Traversal} core
 * can drive every effect uniformly.
 */
package com.github.eschizoid.telescope.internal.optics.instances;
