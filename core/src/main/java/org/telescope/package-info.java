/**
 * Deep-copy DSL for Java records. The whole public surface of the library lives here.
 *
 * <h2>Entry points</h2>
 *
 * <ul>
 *   <li>{@link org.telescope.Telescope} — the DSL. Build a path by chaining {@code .field(...)} /
 *       {@code .each(...)} / {@code .as(...)} / {@code .filter(...)}, then read it ({@code read},
 *       {@code find}, {@code toList}, {@code count}, {@code exists}) or write it ({@code set},
 *       {@code update}, {@code updateAsync}, {@code updateOptional}, {@code updateEither}, {@code
 *       updateValidated}). Single type, no category-theory jargon.
 *   <li>{@link org.telescope.Either} — sealed sum type ({@code Left} / {@code Right}) shipped
 *       in-house so the effectful-update API has no Vavr or Arrow dependency. Used by {@link
 *       org.telescope.Telescope#updateEither}. Short-circuits on the first {@code Left}.
 *   <li>{@link org.telescope.Validated} — sealed sum type ({@code Valid} / {@code Invalid}) that
 *       accumulates errors across every focused element. Used by {@link
 *       org.telescope.Telescope#updateValidated}. The counterpoint to {@code Either}: it gathers
 *       all errors rather than stopping at the first.
 *   <li>{@link org.telescope.Indexed} — a {@code (index, value)} pair, the 0-based flat position of
 *       a focused element. Produced by {@link org.telescope.Telescope#toListIndexed} and consumed
 *       by {@link org.telescope.Telescope#updateIndexed}.
 * </ul>
 *
 * <h2>What's not here</h2>
 *
 * <p>The optic lattice ({@code Lens}, {@code Prism}, {@code Iso}, {@code Traversal}, ...) lives in
 * {@code org.telescope.internal.optics} and is deliberately not exported. {@code Telescope}'s
 * navigation methods build the right optic internally and compose it via the lattice's {@code
 * .then(...)} rules. Users never type these names.
 *
 * <p>The HKT-emulation machinery ({@code Kind}, {@code Applicative}, the four witness/applicative
 * pairs) is internal for the same reason — the four {@code update*} methods on {@code Telescope}
 * box and unbox at the boundary so user code only sees JDK types and the library's own {@code
 * Either} / {@code Validated}.
 *
 * <h2>Records-only, with a POJO bridge</h2>
 *
 * <p>Field navigation rebuilds via a record's canonical constructor. Non-record types throw at
 * runtime with a clear message. For mutable POJOs / Hibernate entities / Lombok classes, bridge
 * once at the seam: {@link org.telescope.Telescope#fromBean} reflectively maps a POJO to a record
 * and back, and the annotation {@link org.telescope.annotations.BeanBridge} is its reflection-free,
 * compile-checked counterpart (annotate the record to have the bridge generated and validated at
 * compile time). The {@link org.telescope.Telescope#from} / {@code .to} / {@code .using} factory
 * covers the simpler record-to-record case via an {@code Iso}.
 *
 * <h2>Codegen</h2>
 *
 * <p>The {@link org.telescope.annotations.Focus} annotation drives an annotation processor that
 * emits per-record optic constants at compile time, eliminating the per-field reflection cost of
 * {@code .field(...)} on hot paths. The generated values plug into the same composition as the
 * reflective path, so a {@code Telescope} built either way behaves identically.
 */
package org.telescope;
