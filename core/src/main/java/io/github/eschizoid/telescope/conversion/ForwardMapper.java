package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.optics.Getter;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.introspection.OpticReport;
import io.github.eschizoid.telescope.introspection.Trace;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A forward-only {@code A → B} mapper produced by {@link Telescope#mapperForward(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)}. The backward direction is not present at the
 * type level — there is no {@code backward(...)} method to call — so the "this mapper is one-way"
 * contract is enforced by the compiler rather than by a runtime throw.
 *
 * <p>Use this when the conversion is genuinely directional — entity → DTO write-only, audit-log
 * projection, normalisation pipeline — and the round-trip ergonomics of {@link Mapper} are not just
 * unused but actively misleading. MapStruct cannot express "this mapper is one-way" in its type
 * system; the typed escape valve here is exactly the differentiator the type system buys.
 *
 * <p>For bidirectional conversions, use {@link Telescope#mapper(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)} and the regular {@link Mapper}.
 */
public final class ForwardMapper<A, B> {

  // Lattice-routed: the read substrate is the internal `Getter<A, B>` (the lattice's read-only
  // optic primitive — the same shape Mapper's bidirectional `Iso<A, B>` weakens to). Stored as
  // the optic, not a raw `Function`, so the lattice carries the field semantics — composition
  // via .then(...) below routes through Getter.then(Getter) rather than ad-hoc Function chains.
  private final Getter<A, B> forward;
  private final Class<A> sourceClass;
  private final Class<B> targetClass;
  // The field-decision trail the deep-mapping engine resolved, surfaced by explain(). Carries the
  // rows resolved by mapperForward(...), and is preserved through the value-only hooks
  // (beforeForward / afterForward) which don't change the field mapping. Empty for bridge-backed
  // and
  // then-composed forward mappers, whose A→C projection has no single flat field table.
  private final List<OpticNode> explainTrail;

  // Zero-dependency observability via java.base's System.Logger — structure at DEBUG on
  // construction,
  // per-conversion value trace at TRACE on forward(). Lazy suppliers, free when the level is off.
  // Named by type pair (shared io.github.eschizoid.telescope.mapper.<A>.<B> namespace with Mapper).
  private static final String LOGGER_PREFIX = "io.github.eschizoid.telescope.mapper.";
  private final Logger logger;

  ForwardMapper(final Getter<A, B> forward, final Class<A> sourceClass, final Class<B> targetClass) {
    this(forward, sourceClass, targetClass, List.of());
  }

  ForwardMapper(
    final Getter<A, B> forward,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final List<OpticNode> explainTrail
  ) {
    this.forward = forward;
    this.sourceClass = sourceClass;
    this.targetClass = targetClass;
    this.explainTrail = List.copyOf(explainTrail);
    this.logger = System.getLogger(LOGGER_PREFIX + sourceClass.getSimpleName() + "." + targetClass.getSimpleName());
    // Structure once at DEBUG (skip trail-less shells). Lazy — only rendered if DEBUG is enabled.
    if (!this.explainTrail.isEmpty()) logger.log(
      Level.DEBUG,
      () -> "forward " + sourceClass.getSimpleName() + " → " + targetClass.getSimpleName() + "\n" + explain()
    );
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> Cross-package factory used by {@link
   * Telescope#mapperForward(Class, Class, io.github.eschizoid.telescope.mapping.MapStep...)}. The
   * supplied {@link java.util.function.Function} is adapted to the lattice's {@link Getter}
   * substrate immediately — the lattice still owns the read shape, this factory is just the
   * cross-package adapter that the {@code Telescope} factory pipes through. External code must not
   * call this directly; use {@link Telescope#mapperForward(Class, Class,
   * io.github.eschizoid.telescope.mapping.MapStep...)}.
   */
  public static <A, B> ForwardMapper<A, B> create(
    final Function<? super A, ? extends B> forward,
    final Class<A> sourceClass,
    final Class<B> targetClass
  ) {
    return create(forward, sourceClass, targetClass, List.of());
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> Same as {@link #create(Function, Class, Class)}
   * plus the introspection trail the deep-mapping engine resolved, so {@link #explain()} can
   * surface the forward-only mapper's field decisions.
   */
  public static <A, B> ForwardMapper<A, B> create(
    final Function<? super A, ? extends B> forward,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final List<OpticNode> explainTrail
  ) {
    final Getter<A, B> getter = forward::apply;
    return new ForwardMapper<>(getter, sourceClass, targetClass, explainTrail);
  }

  /**
   * Describe what this forward mapper does, as a queryable {@link OpticReport} — the field
   * correspondences it resolved, the transformations it applies, the target fields it skips (with
   * reasons), and the source fields it leaves unused. Built from the same pairing decisions the
   * mapper converts with, so it cannot drift. Lenient forward mappers surface the {@code
   * MISSING_SOURCE} skips and {@code UnusedSource} rows a strict {@code Mapper} would reject at
   * construction.
   *
   * @return the structure of this mapper's conversion; never null
   */
  public OpticReport explain() {
    return new OpticReport(explainTrail);
  }

  /**
   * Run this forward mapper against {@code input} and show, per resolved field, the source value
   * flowing to the target value — a {@link Trace} with the value column filled in. Where {@link
   * #explain()} is the static field structure, {@code trace} runs the conversion. Off the hot path.
   *
   * @param input the source value to convert and trace
   * @return the per-field value trace; never null
   */
  public Trace trace(final A input) {
    return MappingTraces.of(input, forward(input), explainTrail);
  }

  /** Forward conversion {@code A → B}. */
  public B forward(final A a) {
    // Null in, null out — matches Mapper.forward's contract and MapStruct's generated null guard.
    if (a == null) return null;
    final B result = forward.get(a);
    // Value trace at TRACE from the already-computed result (never re-running forward, which would
    // recurse through trace). Lazy — the render only runs if TRACE is enabled.
    logger.log(Level.TRACE, () -> MappingTraces.of(a, result, explainTrail).toString());
    return result;
  }

  /** Alias of {@link #forward(Object)}. */
  public B read(final A a) {
    return forward(a);
  }

  /**
   * Compose with another forward-only projection: {@code this.then(next).forward(a) ==
   * next.forward(this.forward(a))}. Routes through the lattice's {@code Getter.then(Getter)}
   * composition — no ad-hoc function chains.
   *
   * <pre>{@code
   * ForwardMapper<Entity, Dto> entityToDto = Telescope.mapperForward(Entity.class, Dto.class, ...);
   * ForwardMapper<Dto, AuditEvent> dtoToAudit = Telescope.mapperForward(Dto.class, AuditEvent.class, ...);
   * ForwardMapper<Entity, AuditEvent> pipeline = entityToDto.then(dtoToAudit);
   * }</pre>
   */
  public <C> ForwardMapper<A, C> then(final ForwardMapper<B, C> next) {
    // Genuine lattice routing: Getter.then(Getter) composes two read-only optics into one. The
    // composition is one method call on the substrate, not an inline lambda closure.
    final Getter<A, C> composed = forward.then(next.forward);
    // No field trail: the A→C projection routes through the intermediate B, so there is no single
    // flat A→C field table to surface. explain() is intentionally empty on a then-composed mapper.
    return new ForwardMapper<>(composed, sourceClass, next.targetClass);
  }

  /**
   * Compose a pre-forward hook: before the structural forward runs, transform the source {@code A}
   * via {@code hook} and feed the result into {@code forward}. Mirrors {@link
   * Mapper#beforeForward(Function)} for the forward-only tier — useful for source normalisation
   * before the projection runs.
   *
   * <p>Lattice-native: routes through {@code Getter.then(Getter)} by lifting {@code hook} as a
   * {@code Getter<A, A>} and composing it before the existing read. Chains compose left-to-right.
   */
  public ForwardMapper<A, B> beforeForward(final Function<? super A, ? extends A> hook) {
    final Getter<A, A> pre = hook::apply;
    // A pre-forward value hook doesn't change which field maps to which — keep the field trail so
    // explain() / trace() stay populated after composition.
    return new ForwardMapper<>(pre.then(forward), sourceClass, targetClass, explainTrail);
  }

  /**
   * Compose a post-forward hook: after the structural forward produces a {@code B}, transform it
   * via {@code hook} before returning. Mirrors {@link Mapper#afterForward(Function)} for the
   * forward-only tier — closes the "stamp a derived value after the projection" gap.
   *
   * <p>Lattice-native: routes through {@code Getter.then(Getter)}.
   */
  public ForwardMapper<A, B> afterForward(final Function<? super B, ? extends B> hook) {
    final Getter<B, B> post = hook::apply;
    // A post-forward value hook doesn't change the field mapping — keep the field trail.
    return new ForwardMapper<>(forward.then(post), sourceClass, targetClass, explainTrail);
  }

  /**
   * Source-aware post-forward hook: receives BOTH the source {@code A} (after any prior {@link
   * #beforeForward(Function)}) AND the structural result {@code B}. MapStruct's
   * {@code @AfterMapping void enrich(Source src, @MappingTarget Dto dto)} equivalent for the
   * forward-only tier.
   *
   * <p>Cannot factor cleanly through a pure {@code Getter.then(Getter)} composition — the hook
   * needs both inputs at the same evaluation site — so the new {@code Getter} closes over both the
   * prior read and the hook in one lambda. Still on-lattice: the substrate stays {@code Getter<A,
   * B>}, not a raw {@code Function}.
   */
  public ForwardMapper<A, B> afterForward(final BiFunction<? super A, ? super B, ? extends B> hook) {
    final Getter<A, B> prior = forward;
    final Getter<A, B> wrapped = a -> hook.apply(a, prior.get(a));
    // A source-aware post hook still doesn't change the field mapping — keep the field trail.
    return new ForwardMapper<>(wrapped, sourceClass, targetClass, explainTrail);
  }

  /**
   * Lift this element-level forward mapper to a {@code ForwardMapper<List<A>, List<B>>}. Element-
   * wise forward via the lattice's {@link Getter#liftList(Getter)} primitive. {@code null} lists
   * round-trip to {@code null}. Forward-only counterpart of {@link Mapper#liftList()}.
   *
   * <pre>{@code
   * ForwardMapper<Entity, Dto> elementToDto = Telescope.mapperForward(Entity.class, Dto.class, ...);
   * ForwardMapper<List<Entity>, List<Dto>> listToList = elementToDto.liftList();
   * }</pre>
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public ForwardMapper<List<A>, List<B>> liftList() {
    return new ForwardMapper<>(Getter.liftList(forward), (Class) List.class, (Class) List.class);
  }

  /** Same as {@link #liftList()} but produces a {@code ForwardMapper<Set<A>, Set<B>>}. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public ForwardMapper<Set<A>, Set<B>> liftSet() {
    return new ForwardMapper<>(Getter.liftSet(forward), (Class) Set.class, (Class) Set.class);
  }

  /** Same as {@link #liftList()} but produces a {@code ForwardMapper<Optional<A>, Optional<B>>}. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public ForwardMapper<Optional<A>, Optional<B>> liftOptional() {
    return new ForwardMapper<>(Getter.liftOptional(forward), (Class) Optional.class, (Class) Optional.class);
  }

  /**
   * Same as {@link #liftList()} but produces a {@code ForwardMapper<Map<K, A>, Map<K, B>>}. Keys
   * are preserved; only the values flow through {@link #forward}.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public <K> ForwardMapper<Map<K, A>, Map<K, B>> liftMapValues() {
    return new ForwardMapper<>(Getter.liftMapValues(forward), (Class) Map.class, (Class) Map.class);
  }

  /** The mapper's source class. */
  public Class<A> sourceClass() {
    return sourceClass;
  }

  /** The mapper's target class. */
  public Class<B> targetClass() {
    return targetClass;
  }
}
