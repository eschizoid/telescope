/**
 * Telescope — an optics-based DSL for Java records and POJOs. One type ({@link
 * io.github.eschizoid.telescope.Telescope Telescope&lt;S, A&gt;}) drives deep navigation, immutable
 * update, effectful update, bidirectional mapping between record-shaped and bean-shaped graphs, and
 * sealed-type narrowing. Optional compile-time codegen ({@code @Focus} / {@code @BeanFocus} /
 * {@code @Bridge}) eliminates per-call reflection on hot paths without changing the API users
 * write.
 *
 * <p>Two packages are exported:
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope} — the DSL surface. {@link
 *       io.github.eschizoid.telescope.Telescope} (navigation + read + write + effects + deep
 *       mapping factory), {@link io.github.eschizoid.telescope.conversion.Mapper} (bidirectional
 *       graph mapper with {@code forward} / {@code backward} / {@code patch} / {@code asTelescope}
 *       / container- lift surface), the {@link io.github.eschizoid.telescope.Either} / {@link
 *       io.github.eschizoid.telescope.Validated} sealed effect types, {@link
 *       io.github.eschizoid.telescope.Indexed} for indexed traversals, the multi-edit primitive
 *       {@link io.github.eschizoid.telescope.Edit}, and the row-builder DSL that the deep-mapping
 *       factory accepts as varargs ({@link io.github.eschizoid.telescope.mapping.Mapping#to to},
 *       {@link io.github.eschizoid.telescope.mapping.Mapping#via via}, {@link
 *       io.github.eschizoid.telescope.mapping.WriteHint#writeBean writeBean} / {@link
 *       io.github.eschizoid.telescope.mapping.WriteHint#writeBeans writeBeans}).
 *   <li>{@link io.github.eschizoid.telescope.annotations} — compile-time markers for the codegen
 *       processors: {@code @Focus} (records), {@code @BeanFocus} (POJOs, also for Lombok-annotated
 *       classes via the {@code telescope-lombok} module), {@code @Bridge} (compile-checked
 *       cross-paradigm conversion).
 * </ul>
 *
 * <p>The {@code io.github.eschizoid.telescope.internal} packages are deliberately not exported.
 * They hold the proven optic lattice ({@code Iso} / {@code Lens} / {@code Prism} / {@code Affine} /
 * {@code Traversal} / {@code Getter} / {@code Setter} / {@code Fold}), the HKT-emulation machinery
 * ({@code Kind} / {@code Applicative}) and per-effect witnesses ({@code OptionalK}, {@code
 * EitherK}, {@code ValidatedK}, {@code CompletableFutureK}), plus the reflection helpers ({@code
 * Records}, {@code Beans}, {@code Reflective}, {@code LambdaIntrospection}). Users never type these
 * names — the public API surfaces them as plain method calls on {@code Telescope} and {@code
 * Mapper}.
 *
 * <p>Promoting any internal type to public API in a future version is one {@code exports} line
 * away; until then, encapsulation is enforced at the JPMS level.
 */
module io.github.eschizoid.telescope {
  requires transitive io.github.eschizoid.telescope.internal;

  exports io.github.eschizoid.telescope;
  exports io.github.eschizoid.telescope.annotations;
  exports io.github.eschizoid.telescope.conversion;
  exports io.github.eschizoid.telescope.effects;
  exports io.github.eschizoid.telescope.mapping;
}
