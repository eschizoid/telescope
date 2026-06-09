/**
 * The DSL surface of telescope — an optics-based library for navigating, updating, mapping, and
 * lifting through effects over Java records and POJOs. One {@link
 * io.github.eschizoid.telescope.Telescope Telescope&lt;S, A&gt;} type carries the navigation path
 * and every terminal operation (read / write / single-shot effects / multi-edit / deep mapping);
 * complements {@link io.github.eschizoid.telescope.conversion.Mapper Mapper&lt;A, B&gt;} for the
 * cases that benefit from sparse-overlay patching or explicit container lifting.
 *
 * <h2>Entry points</h2>
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.Telescope} — the DSL. Build a path by chaining {@code
 *       .field(...)} / {@code .each(...)} / {@code .as(...)} / {@code .filter(...)}, then read it
 *       ({@code read}, {@code find}, {@code toList}, {@code count}, {@code exists}) or write it
 *       ({@code set}, {@code update}, {@code updateAsync}, {@code updateOptional}, {@code
 *       updateEither}, {@code updateValidated}). Single type, no category-theory jargon.
 *   <li>{@link io.github.eschizoid.telescope.Either} — sealed sum type ({@code Left} / {@code
 *       Right}) shipped in-house so the effectful-update API has no Vavr or Arrow dependency. Used
 *       by {@link io.github.eschizoid.telescope.Telescope#updateEither}. Short-circuits on the
 *       first {@code Left}.
 *   <li>{@link io.github.eschizoid.telescope.Validated} — sealed sum type ({@code Valid} / {@code
 *       Invalid}) that accumulates errors across every focused element. Used by {@link
 *       io.github.eschizoid.telescope.Telescope#updateValidated}. The counterpoint to {@code
 *       Either}: it gathers all errors rather than stopping at the first.
 *   <li>{@link io.github.eschizoid.telescope.Indexed} — a {@code (index, value)} pair, the 0-based
 *       flat position of a focused element. Produced by {@link
 *       io.github.eschizoid.telescope.Telescope#toListIndexed} and consumed by {@link
 *       io.github.eschizoid.telescope.Telescope#updateIndexed}.
 * </ul>
 *
 * <h2>What's not here</h2>
 *
 * <p>The optic lattice ({@code Lens}, {@code Prism}, {@code Iso}, {@code Traversal}, ...) lives in
 * {@code io.github.eschizoid.telescope.internal.optics} and is deliberately not exported. {@code
 * Telescope}'s navigation methods build the right optic internally and compose it via the lattice's
 * {@code .then(...)} rules. Users never type these names.
 *
 * <p>The HKT-emulation machinery ({@code Kind}, {@code Applicative}, the four witness/applicative
 * pairs) is internal for the same reason — the four {@code update*} methods on {@code Telescope}
 * box and unbox at the boundary so user code only sees JDK types and the library's own {@code
 * Either} / {@code Validated}.
 *
 * <h2>Records and beans, uniformly</h2>
 *
 * <p>{@code .field(...)} navigation rebuilds via a record's canonical constructor or a bean's
 * auto-detected write strategy. The deep recursive mapping factory {@link
 * io.github.eschizoid.telescope.Telescope#map(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)} handles record↔record, POJO↔POJO, and any
 * cross-paradigm mix at any depth — the per-side {@code Reflective} is picked independently from
 * each class. The annotation {@link io.github.eschizoid.telescope.annotations.Bridge} is the
 * reflection-free, compile-checked counterpart (annotate the record to have the bridge generated
 * and validated at compile time). The {@link io.github.eschizoid.telescope.Telescope#from} / {@code
 * .to} / {@code .using} factory covers the simpler hand-written conversion case via an {@code Iso}.
 *
 * <h2>Codegen</h2>
 *
 * <p>The {@link io.github.eschizoid.telescope.annotations.Focus} annotation drives an annotation
 * processor that emits per-record optic constants at compile time, eliminating the per-field
 * reflection cost of {@code .field(...)} on hot paths. The generated values plug into the same
 * composition as the reflective path, so a {@code Telescope} built either way behaves identically.
 */
package io.github.eschizoid.telescope;
