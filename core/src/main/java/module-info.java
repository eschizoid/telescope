/**
 * Telescope — an optics-based DSL for Java records and POJOs. One type ({@link
 * io.github.telescopeorganization.telescope.Telescope Telescope&lt;S, A&gt;}) drives deep
 * navigation, immutable update, effectful update, bidirectional mapping between record-shaped and
 * bean-shaped graphs, and sealed-type narrowing. Optional compile-time codegen ({@code @Focus} /
 * {@code @BeanFocus} / {@code @Bridge}) eliminates per-call reflection on hot paths without
 * changing the API users write.
 *
 * <p>Five packages are exported:
 *
 * <ul>
 *   <li>{@link io.github.telescopeorganization.telescope} — the root DSL surface. {@link
 *       io.github.telescopeorganization.telescope.Telescope} (navigation + read + write + effects +
 *       deep mapping factory), {@link io.github.telescopeorganization.telescope.Indexed} for
 *       indexed traversals, and the multi-edit primitive {@link
 *       io.github.telescopeorganization.telescope.Edit}.
 *   <li>{@link io.github.telescopeorganization.telescope.conversion} — {@link
 *       io.github.telescopeorganization.telescope.conversion.Mapper} (bidirectional graph mapper
 *       with {@code forward} / {@code backward} / {@code patch} / {@code asTelescope} /
 *       container-lift surface) plus the {@link
 *       io.github.telescopeorganization.telescope.conversion.From} / {@link
 *       io.github.telescopeorganization.telescope.conversion.To} fluent factory used by {@link
 *       io.github.telescopeorganization.telescope.Telescope#from(Class)}.
 *   <li>{@link io.github.telescopeorganization.telescope.mapping} — the row-builder DSL that the
 *       deep-mapping factory accepts as varargs: {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping#to to}, {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping#via via}, {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping#drop drop}, {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping#toOneWay toOneWay}, {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping#constant constant}, {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping#compute compute}, {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping#when when}, {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping#enumTo enumTo}, {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping#toOrElse toOrElse}, {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping#zip zip} (full set on {@link
 *       io.github.telescopeorganization.telescope.mapping.Mapping}); sealed {@link
 *       io.github.telescopeorganization.telescope.mapping.MapExtractStep} backing {@link
 *       io.github.telescopeorganization.telescope.Telescope#fromMap} for untyped sources; {@link
 *       io.github.telescopeorganization.telescope.mapping.MergeStep} backing {@link
 *       io.github.telescopeorganization.telescope.Telescope#merge}; {@link
 *       io.github.telescopeorganization.telescope.mapping.WriteHint} / {@link
 *       io.github.telescopeorganization.telescope.mapping.NullHint} hints.
 *   <li>{@link io.github.telescopeorganization.telescope.effects} — {@link
 *       io.github.telescopeorganization.telescope.effects.Either} / {@link
 *       io.github.telescopeorganization.telescope.effects.Validated} sealed effect types backing
 *       {@code updateEither} and {@code updateValidated}.
 *   <li>{@link io.github.telescopeorganization.telescope.annotations} — compile-time markers for
 *       the codegen processors: {@code @Focus} (records), {@code @BeanFocus} (POJOs, also for
 *       Lombok-annotated classes via the {@code telescope-lombok} module), {@code @Bridge}
 *       (compile-checked cross-paradigm conversion — records or classes, model-anchored or carrier
 *       form, with {@code lenient = true} for small-DTO → large-entity, or a mapper field whose
 *       rows it compiles).
 * </ul>
 *
 * <p>The {@code io.github.telescopeorganization.telescope.internal} packages are deliberately not
 * exported. They hold the proven optic lattice ({@code Iso} / {@code Lens} / {@code Prism} / {@code
 * Affine} / {@code Traversal} / {@code Getter} / {@code Setter} / {@code Fold}), the HKT-emulation
 * machinery ({@code Kind} / {@code Applicative}) and per-effect witnesses ({@code OptionalK},
 * {@code EitherK}, {@code ValidatedK}, {@code CompletableFutureK}), plus the reflection helpers
 * ({@code Records}, {@code Beans}, {@code Reflective}, {@code LambdaIntrospection}, {@code
 * MetadataHolderProbe}, {@code NullDefaults}). Users never type these names — the public API
 * surfaces them as plain method calls on {@code Telescope} and {@code Mapper}.
 *
 * <p>Promoting any internal type to public API in a future version is one {@code exports} line
 * away; until then, encapsulation is enforced at the JPMS level.
 */
module io.github.telescopeorganization.telescope {
  requires transitive io.github.telescopeorganization.telescope.internal;

  exports io.github.telescopeorganization.telescope;
  exports io.github.telescopeorganization.telescope.annotations;
  exports io.github.telescopeorganization.telescope.conversion;
  exports io.github.telescopeorganization.telescope.effects;
  exports io.github.telescopeorganization.telescope.inject;
  exports io.github.telescopeorganization.telescope.introspection;
  exports io.github.telescopeorganization.telescope.mapping;

  // Carrier-form @Bridge converters register themselves through this SPI so mapperForward can
  // discover them by (source, target) regardless of the package they were emitted in.
  uses io.github.telescopeorganization.telescope.conversion.BridgeProvider;

  // A generated @FromMap binder registers itself through this SPI, so fromMap can tell a type with
  // a binder from one without, whichever compilation produced it and in a native image.
  uses io.github.telescopeorganization.telescope.conversion.FromMapProvider;
}
