/**
 * Per-effect {@code Kind}/{@code Applicative} witness pairs that power the four effectful {@code
 * update*} methods on {@link io.github.telescopeorganization.telescope.Telescope}. Internal to the
 * library — boxing into the witness happens at the DSL boundary and is invisible to user code.
 *
 * <ul>
 *   <li>{@link io.github.telescopeorganization.telescope.runtime.instances.OptionalK} — {@link
 *       java.util.Optional} witness; backs {@code updateOptional}.
 *   <li>{@link io.github.telescopeorganization.telescope.runtime.instances.EitherK} — {@link
 *       io.github.telescopeorganization.telescope.effects.Either} witness; backs {@code
 *       updateEither} and short-circuits on the first {@code Left}.
 *   <li>{@link io.github.telescopeorganization.telescope.runtime.instances.ValidatedK} — {@link
 *       io.github.telescopeorganization.telescope.effects.Validated} witness; backs {@code
 *       updateValidated} and accumulates errors across every focused element.
 *   <li>{@link io.github.telescopeorganization.telescope.runtime.instances.CompletableFutureK} —
 *       {@link java.util.concurrent.CompletableFuture} witness; backs {@code updateAsync}.
 * </ul>
 *
 * <p>Each instance pairs a {@code Kind} encoding (lightweight HKT emulation) with an {@code
 * Applicative} so the same {@link
 * io.github.telescopeorganization.telescope.internal.optics.Traversal} core can drive every effect
 * uniformly.
 */
package io.github.telescopeorganization.telescope.runtime.instances;
