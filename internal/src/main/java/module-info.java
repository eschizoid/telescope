/**
 * Internal substrate of the telescope optics DSL — qualified-exported to {@code
 * io.github.eschizoid.telescope} (the public {@code telescope-api} module) and to nothing else.
 *
 * <p>External consumers of telescope add {@code telescope-api} to their build and get the public
 * surface. The {@code telescope-internal} artifact comes along transitively but its packages are
 * NOT visible to user modules — JPMS qualified exports enforce this at compile time. Users
 * literally cannot type {@code Iso}, {@code Lens}, {@code Prism}, {@code Affine}, or {@code
 * Traversal} in their own code; attempting to do so fails with {@code package ... is not visible}.
 *
 * <p>Contents:
 *
 * <ul>
 *   <li>{@code internal.optics} — the proven optic lattice (Iso / Lens / Prism / Affine /
 *       Traversal / Getter / Setter / Fold) with the composition rules pinned by {@code
 *       OpticLawsTest} in {@code :api}.
 *   <li>{@code internal.optics.collections} — runtime dispatch for List / Set / Iterable / Map
 *       values / Optional traversal.
 *   <li>{@code internal.optics.instances} — per-effect Applicative witnesses
 *       ({@code CompletableFutureK}, {@code OptionalK}, {@code EitherK}, {@code ValidatedK}) used
 *       by the four effectful update terminals on the public {@code Telescope}.
 *   <li>{@code internal} — reflection helpers ({@code Records}, {@code Beans}, {@code Reflective},
 *       {@code LambdaIntrospection}) and the codegen-metadata-holder probe.
 * </ul>
 *
 * <p>Do NOT add {@code telescope-internal} as a direct dependency. The artifact only exists
 * because publishing the optic lattice as a separate JPMS module is the JPMS-blessed way to keep
 * internal types invisible to user code while still letting {@code telescope-api} type-safely
 * reference them.
 */
module io.github.eschizoid.telescope.internal {
    exports io.github.eschizoid.telescope.internal           to io.github.eschizoid.telescope;
    exports io.github.eschizoid.telescope.internal.optics    to io.github.eschizoid.telescope;
    exports io.github.eschizoid.telescope.internal.optics.collections to io.github.eschizoid.telescope;
}
