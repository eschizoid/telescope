/**
 * Sealed sum types backing the effectful {@code update*} methods on {@link
 * io.github.eschizoid.telescope.Telescope}. Shipped in-house so the effectful-update API has no
 * Vavr or Arrow dependency.
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.effects.Either} — {@code Left} / {@code Right} sum.
 *       Used by {@link io.github.eschizoid.telescope.Telescope#updateEither}; short-circuits on the
 *       first {@code Left} encountered during traversal.
 *   <li>{@link io.github.eschizoid.telescope.effects.Validated} — {@code Valid} / {@code Invalid}
 *       sum. Used by {@link io.github.eschizoid.telescope.Telescope#updateValidated}; accumulates
 *       all {@code Invalid} errors across every focused element rather than stopping at the first.
 *       Counterpoint to {@code Either}.
 * </ul>
 *
 * <p>Both types are immutable, pattern-matchable via {@code switch} on the sealed permits, and
 * carry no library-specific machinery — they're plain JDK 25 sealed records. The HKT witnesses that
 * bridge them into the internal lattice ({@code EitherK} / {@code ValidatedK}) live under {@code
 * io.github.eschizoid.telescope.runtime.instances} and are invisible to user code.
 */
package io.github.eschizoid.telescope.effects;
