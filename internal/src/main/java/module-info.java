/**
 * Internal substrate of the telescope optics DSL — qualified-exported to {@code
 * io.github.eschizoid.telescope} (the public {@code telescope-core} module), its reflection
 * helpers also to {@code telescope-jpa} and its pairing spec also to {@code telescope-codegen}.
 *
 * <p>External consumers of telescope add {@code telescope-core} to their build and get the public
 * surface. The {@code telescope-internal} artifact comes along transitively but its packages are
 * NOT visible to user modules — JPMS qualified exports enforce this at compile time. Users
 * literally cannot type {@code Iso}, {@code Lens}, {@code Prism}, {@code Affine}, or {@code
 * Traversal} in their own code; attempting to do so fails with {@code package ... is not visible}.
 *
 * <p>Contents:
 *
 * <ul>
 *   <li>{@code internal.optics} — the proven optic lattice (Iso / Lens / Prism / Affine / Traversal
 *       / Getter / Setter / Fold) plus {@code Kind} / {@code Applicative} HKT-emulation, with the
 *       composition rules pinned by {@code OpticLawsTest} in this module's test sources.
 *   <li>{@code internal.optics.collections} — runtime dispatch for List / Set / Iterable / Map
 *       values / Optional traversal.
 *   <li>{@code internal} — reflection helpers ({@code Records}, {@code Beans}, {@code Reflective},
 *       {@code LambdaIntrospection}, {@code NullDefaults}) and the codegen {@code
 *       MetadataHolderProbe}. The probe looks up a sibling {@code <X>FieldOptics} metadata holder
 *       per target class via a {@code ClassValue<Optional<HolderRef>>} cache — when present, the
 *       runtime dispatch sites read the codegen-emitted {@code Telescope<X, FieldType>} constants
 *       directly; when absent, they fall through to the LMF-backed reflective path. This module
 *       stays compile-time-independent of {@code :core}.
 * </ul>
 *
 * <p>The per-effect {@code Applicative} witnesses ({@code CompletableFutureK}, {@code OptionalK},
 * {@code EitherK}, {@code ValidatedK}) live in {@code :core} under {@code
 * io.github.eschizoid.telescope.runtime.instances} — they couple to the public {@code Either} /
 * {@code Validated} effect types and so are naturally housed alongside them.
 *
 * <p>Do NOT add {@code telescope-internal} as a direct dependency. The artifact only exists because
 * publishing the optic lattice as a separate JPMS module is the JPMS-blessed way to keep internal
 * types invisible to user code while still letting {@code telescope-core} type-safely reference
 * them.
 */
module io.github.eschizoid.telescope.internal {
  // telescope-jpa takes the native-image flag and the MethodHandle accessor closures from here, so
  // its proxy accessors follow the same substrate rule as this module's own.
  exports io.github.eschizoid.telescope.internal to io.github.eschizoid.telescope, io.github.eschizoid.telescope.jpa;
  exports io.github.eschizoid.telescope.internal.optics to io.github.eschizoid.telescope;
  exports io.github.eschizoid.telescope.internal.optics.collections to io.github.eschizoid.telescope;
  // The shared pairing decision spec: the runtime (:core) and the compile-time verifier (:codegen)
  // consume one rule set so construction-time and compile-time diagnostics cannot drift.
  exports io.github.eschizoid.telescope.internal.pairing
    to io.github.eschizoid.telescope, io.github.eschizoid.telescope.codegen;

  // telescope-core provides the one implementation, which consults the proxy resolvers registered
  // through its public SPI; this module holds no provider-specific code.
  uses io.github.eschizoid.telescope.internal.ProxyClasses;
}
