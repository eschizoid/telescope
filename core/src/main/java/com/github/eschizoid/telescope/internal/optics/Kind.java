package com.github.eschizoid.telescope.internal.optics;

/**
 * Defunctionalized marker for higher-kinded types — the lightweight HKT-encoding trick from Yallop
 * &amp; White's 2014 paper, lifted from {@code higher-kinded-j}. Java has no native {@code F[_]},
 * so {@link Kind} stands in: {@code Kind<F, A>} represents "some type constructor {@code F} applied
 * to {@code A}," with {@code F} a phantom witness type.
 *
 * <p>Internal to the library. Users of {@link com.github.eschizoid.telescope.Telescope} never see
 * {@code Kind}: the four {@code update*} methods box and unbox at the boundary, so the user-facing
 * types are JDK standards ({@link java.util.concurrent.CompletableFuture}, {@link
 * java.util.Optional}) and the library's own {@link com.github.eschizoid.telescope.Either} / {@link
 * com.github.eschizoid.telescope.Validated}.
 *
 * <p>Each effect has a corresponding witness implementing {@link Witness} (e.g. {@code OptionalK}),
 * a carrier that implements {@code Kind<ThatWitness, A>}, and an {@link Applicative} instance
 * describing how to combine effectful values of that shape.
 */
public interface Kind<F extends Kind.Witness, A> {
  /**
   * Phantom marker implemented by per-effect witness classes ({@code OptionalK}, {@code
   * CompletableFutureK}, ...). Witness classes are never instantiated; their {@link Class} is the
   * type-level handle that ties a {@link Kind} carrier to its {@link Applicative}.
   */
  interface Witness {}
}
