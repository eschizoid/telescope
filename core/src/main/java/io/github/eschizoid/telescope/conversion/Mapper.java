package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.AdderCalls;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.pairing.BeanWriteStrategy;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.introspection.OpticReport;
import io.github.eschizoid.telescope.introspection.Trace;
import io.github.eschizoid.telescope.mapping.MapStep;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A bidirectional mapper produced by the deep recursive factory {@link Telescope#mapper(Class,
 * Class, io.github.eschizoid.telescope.mapping.MapStep...)}. Wraps an {@link Iso} for the
 * forward/backward conversion and (optionally) a per-target-component patch table for sparse {@link
 * #patch} overlays.
 *
 * <p>Works for any combination of record and bean classes — the source and target side each pick
 * their own {@link Reflective} dispatch at construction, so {@code patch(...)} reads the partial
 * value through the right reflective and rebuilds the source through its writer (canonical
 * constructor for records, auto-detected write strategy for beans).
 */
public class Mapper<A, B> {

  /**
   * <b>Module-internal seam — NOT public API.</b> One entry of the patch table — when overlaying a
   * partially-populated target onto a source, each non-null target component value is fed to {@link
   * #backward} and written to {@link #sourceField} on the source.
   *
   * <p>Declared {@code public} solely so the cross-package {@link
   * io.github.eschizoid.telescope.DeepMap} engine can construct entries when building a {@link
   * Mapper} via {@link Mapper#Mapper(Iso, Class, Class, Map)}. The raw {@link Function} field is
   * part of that internal contract; external code must not depend on this record's shape. Treat as
   * private to the module — it may change or disappear without a deprecation cycle.
   */
  public record PatchEntry(
    String sourceField,
    Function<Object, Object> backward,
    BiFunction<Object, Object, Object> overlay,
    Function<Object, Object> read
  ) {
    /** An entry whose value only ever converts into {@code sourceField}. */
    public PatchEntry(final String sourceField, final Function<Object, Object> backward) {
      this(sourceField, backward, null, null);
    }
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> One case of a mapper between two sealed
   * interfaces: a case of the source, the case of the target it converts to, and the mapper between
   * the two, which {@link #patch} and {@link #into} hand a value of that case to. Declared {@code
   * public} for the same reason as {@link PatchEntry}.
   */
  public record CaseEntry(Class<?> sourceCase, Class<?> targetCase, Mapper<?, ?> mapper) {}

  private final Iso<A, B> iso;
  private final Class<A> sourceClass;
  private final Class<B> targetClass;
  // The reflective that carries the call's write hints for beans, or null where it has none; the
  // two sides below are it for a bean and the record reflective for a record.
  private final Reflective beanRefl;
  private final Reflective sourceRefl;
  private final Reflective targetRefl;
  private volatile IntoPlan intoPlan;
  // The accessors patchInto writes each property of one target class through, bound the first time
  // a partial sends the property.
  private volatile PatchIntoPlan patchIntoPlan;
  private final Map<String, PatchEntry> patchByTargetField;
  // Non-null for a mapper between two sealed interfaces, one entry per case of the source: patch
  // and into hand a value to the mapper of the case it belongs to.
  private final List<CaseEntry> cases;
  // Non-null for a pair converted through its generated bridge, whose own patch is the overlay.
  private final BiFunction<A, B, A> patcher;
  // A null patchByTargetField: the mapper has no per-field table to overlay or write through.
  private static final String UNSUPPORTED =
    "This mapper does not support %s: Telescope.merge produces a forward-only mapper (the " +
    "multi-source case has no general inverse), and a lift* mapper's root is a container with no " +
    "fields to overlay. Use forward(...) instead.";
  // Folded hook chains — null = no hook. Composed by repeated calls to before*/after*. Each side
  // is a single Function/BiFunction reference at call time so HotSpot stays monomorphic regardless
  // of chain depth (avoids the megamorphic Iso.of cliff that the per-hook-Iso composition shape
  // produced at 3+ hooks).
  //
  // Lattice mandate carve-out — these are NOT routed through the optic lattice on purpose. The
  // lattice's bidirectional substrate (`Iso<A, B>`) requires the round-trip law `from(to(a)) == a`.
  // A user-supplied hook like `e -> e.normalised()` has no inverse — wrapping it as
  // `Iso.of(hook, identity)` would construct a fake Iso that silently violates the laws
  // `OpticLawsTest` pins. That's worse than admitting the hook isn't lattice-shaped. The Telescope-
  // level equivalents (`Telescope.after/before`) get away with the partial-Iso shape
  // because Telescope is read-or-write through a Traversal — there's no single-value round-trip
  // through both legs. `Mapper.forward(a)` and `Mapper.backward(b)` ARE both surfaces of a single
  // contract, so the lattice-routed shape would break composition. The four hook fields below own
  // that responsibility instead, and the engine `forward` / `backward` methods (just above)
  // compose them around the underlying iso explicitly.
  private final Function<A, A> preForward;
  private final BiFunction<A, B, B> postForward;
  private final Function<B, B> preBackward;
  private final BiFunction<B, A, A> postBackward;
  // The OpticNode rows the deep-mapping engine resolved for this mapper, captured at construction
  // and surfaced by explain(). Empty for lifted mappers (list / set / optional / map-value shells
  // have no top-level field table) and carried unchanged through the hook composers (before*/after*
  // don't alter the field mapping).
  private final List<OpticNode> explainTrail;

  // Whether a value trace would show a field: a trail of only the node naming the generated bridge
  // a mapper runs has none, so its forward skips the logger as a bridge called directly does.
  private final boolean traced;

  // The generated bridge this mapper converts through, when it is one with no hooks, which the
  // class generated for a @Bridge field may then specialise. Null for every other mapper.
  private final BridgeFn<A, B> generated;

  /** A generated bridge's two directions as an {@link Iso}, each one call into the bridge. */
  private static final class GeneratedIso<A, B> implements Iso<A, B> {

    private final BridgeFn<A, B> fn;

    GeneratedIso(final BridgeFn<A, B> fn) {
      this.fn = fn;
    }

    @Override
    public B to(final A source) {
      return fn.forward(source);
    }

    @Override
    public A from(final B value) {
      return fn.backward(value);
    }
  }

  private static boolean traced(final List<OpticNode> trail) {
    for (final var node : trail) if (!(node instanceof OpticNode.Declared)) return true;
    return false;
  }

  // Zero-dependency observability via java.base's System.Logger: the structure (explain) is logged
  // once at DEBUG on construction, and the per-conversion value trace at TRACE on each forward().
  // Both use lazy Supplier overloads, so nothing renders unless the level is enabled — a single
  // isLoggable check when off. Named by type pair so one mapper can be enabled from the app's
  // logging config without touching code (e.g. io.github.eschizoid.telescope.mapper.UserDto.User).
  // The pair uses simple class names for readable, greppable logger names; two pairs sharing a
  // simple name across different packages collide onto one logger (no independent level control) —
  // an accepted trade-off over verbose fully-qualified names for the common distinct-name case.
  private static final String LOGGER_PREFIX = "io.github.eschizoid.telescope.mapper.";
  private final Logger logger;

  /**
   * <b>Module-internal seam — NOT public API.</b> Construct a mapper directly from an {@link Iso},
   * the source/target classes, and a (possibly empty) patch table keyed by target component
   * /property name.
   *
   * <p>Package-private: only same-package callers ({@code DeepMap}, {@link Telescope}, and the
   * {@code lift*} helpers below) construct mappers. External code uses {@link Telescope#mapper(
   * Class, Class, MapStep...)}.
   */
  Mapper(
    final Iso<A, B> iso,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final Map<String, PatchEntry> patchByTargetField
  ) {
    this(iso, sourceClass, targetClass, patchByTargetField, null, null, null, null, List.of(), null, null, null);
  }

  private Mapper(
    final Iso<A, B> iso,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final Map<String, PatchEntry> patchByTargetField,
    final Function<A, A> preForward,
    final BiFunction<A, B, B> postForward,
    final Function<B, B> preBackward,
    final BiFunction<B, A, A> postBackward,
    final List<OpticNode> explainTrail,
    final List<CaseEntry> cases,
    final BiFunction<A, B, A> patcher,
    final Reflective beanRefl
  ) {
    this.iso = iso;
    this.cases = cases == null ? null : List.copyOf(cases);
    this.patcher = patcher;
    this.sourceClass = sourceClass;
    this.targetClass = targetClass;
    this.beanRefl = beanRefl;
    this.sourceRefl = beanRefl == null || sourceClass.isRecord() ? Reflective.of(sourceClass) : beanRefl;
    this.targetRefl = beanRefl == null || targetClass.isRecord() ? Reflective.of(targetClass) : beanRefl;
    // Defensive copy — patch behavior must not mutate after construction. The copy keeps the
    // table's order, which is the order patch converts the partial's fields in; Map.copyOf would
    // iterate in an order that changes from one JVM run to the next. null = patch unsupported (see
    // patch()).
    this.patchByTargetField =
      patchByTargetField == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(patchByTargetField));
    this.preForward = preForward;
    this.postForward = postForward;
    this.preBackward = preBackward;
    this.postBackward = postBackward;
    this.explainTrail = List.copyOf(explainTrail);
    this.traced = traced(this.explainTrail);
    this.generated =
      iso instanceof GeneratedIso<A, B> bridge &&
      preForward == null &&
      postForward == null &&
      preBackward == null &&
      postBackward == null
        ? bridge.fn
        : null;
    this.logger = System.getLogger(LOGGER_PREFIX + sourceClass.getSimpleName() + "." + targetClass.getSimpleName());
    // Structure once at DEBUG (skip the trail-less lifted shells, which would just log "(empty
    // optic)"). Guarded so the Supplier isn't built when DEBUG is off.
    if (!this.explainTrail.isEmpty() && logger.isLoggable(Level.DEBUG)) logger.log(
      Level.DEBUG,
      () -> "map " + sourceClass.getSimpleName() + " → " + targetClass.getSimpleName() + "\n" + explain()
    );
  }

  /**
   * Codegen-support seam: the state of {@code built}, a mapper over a generated bridge with no
   * hooks, for the class the {@code @Bridge} processor generates for a field. That class overrides
   * {@link #forward}, {@link #backward} and {@link #patch}, and {@link #into} where the bridge has
   * one, with one static call each into the field's bridge, so a call through the field's constant
   * is a call the JIT resolves to the bridge and inlines; every other method is this class's own,
   * and final. {@code grant} is the {@link BridgeGrant} the field's provider is handed when the
   * field's factory call is dispatched to it, which keeps a class written by hand from extending
   * this one by accident; a registered provider of one's own, or reflection on the class path, can
   * still obtain it.
   *
   * @throws IllegalArgumentException if {@code grant} is not the one core hands out, or {@code
   *     built} is not a mapper over a generated bridge with no hooks
   */
  protected Mapper(final Mapper<A, B> built, final BridgeGrant grant) {
    if (grant != BridgeGrant.INSTANCE) throw new IllegalArgumentException(
      "Only the class generated for a @Bridge field extends Mapper, with the grant its provider is handed"
    );
    if (built.generated == null) throw new IllegalArgumentException(
      "Only a mapper over a generated bridge with no hooks is specialised by a generated class"
    );
    this.iso = built.iso;
    this.sourceClass = built.sourceClass;
    this.targetClass = built.targetClass;
    this.beanRefl = built.beanRefl;
    this.sourceRefl = built.sourceRefl;
    this.targetRefl = built.targetRefl;
    this.patchByTargetField = built.patchByTargetField;
    this.cases = built.cases;
    this.patcher = built.patcher;
    this.preForward = null;
    this.postForward = null;
    this.preBackward = null;
    this.postBackward = null;
    this.explainTrail = built.explainTrail;
    this.traced = built.traced;
    this.generated = built.generated;
    this.logger = built.logger;
  }

  /**
   * Build a {@code Mapper<A, B>} from a forward/backward function pair and the source/target
   * classes. Cross-package factory used by {@code DeepMap#resolveMapper(Class, Class,
   * io.github.eschizoid.telescope.mapping.MapStep[])} — takes only public {@link Function} types so
   * the lattice {@code Iso} never appears in this class's public signatures even after {@code
   * Mapper} moves to its own sub-package.
   *
   * <p>The {@code patchByTargetField} map is keyed by target-component name; an empty map means no
   * sparse-overlay semantics for {@link #patch}.
   */
  public static <A, B> Mapper<A, B> create(
    final Function<? super A, ? extends B> forward,
    final Function<? super B, ? extends A> backward,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final Map<String, PatchEntry> patchByTargetField
  ) {
    return create(forward, backward, sourceClass, targetClass, patchByTargetField, List.of());
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> Same as {@link #create(Function, Function, Class,
   * Class, Map)} plus the introspection trail the deep-mapping engine resolved for this mapper.
   * {@code DeepMap#resolveMapper} passes the captured {@link OpticNode} list here so {@link
   * #explain()} can surface it.
   */
  public static <A, B> Mapper<A, B> create(
    final Function<? super A, ? extends B> forward,
    final Function<? super B, ? extends A> backward,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final Map<String, PatchEntry> patchByTargetField,
    final List<OpticNode> explainTrail
  ) {
    return new Mapper<>(
      Iso.of(forward, backward),
      sourceClass,
      targetClass,
      patchByTargetField,
      null,
      null,
      null,
      null,
      explainTrail,
      null,
      null,
      null
    );
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> Store an already-composed leaf {@link Iso}
   * directly, without the {@code Iso.of(forward, backward)} re-wrap {@link #create(Function,
   * Function, Class, Class, Map, List)} performs. {@code DeepMap#resolveMapper} holds the leaf Iso
   * the deep-mapping engine built; passing it straight through removes one virtual {@code Iso.to}
   * hop from every {@code forward}/{@code backward} call. Same lattice value, one fewer wrapper.
   * {@code beanRefl} carries the call's write hints for beans: {@link #patch} rebuilds a bean
   * source with it, so a patch builds the bean the way backward does, and {@link #into} reads from
   * it whether the target is filled through its adders. Null rebuilds either side as {@link
   * Reflective#of} does.
   *
   * <p>The {@code exports} warning is suppressed because the signature names internal types ({@link
   * Iso}, {@link Reflective}). It is fenced by the same JPMS containment {@code Telescope.mapped}
   * relies on: both are qualified-exported from {@code :internal} to {@code :core} only, so no
   * downstream module can resolve — let alone call — this method; it stays invisible to real API
   * consumers. It is the only way {@code DeepMap.resolveMapper} (a different package) can hand its
   * leaf Iso to {@code Mapper}'s package-private constructor without the double wrap.
   */
  @SuppressWarnings("exports")
  public static <A, B> Mapper<A, B> ofIso(
    final Iso<A, B> iso,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final Map<String, PatchEntry> patchByTargetField,
    final List<OpticNode> explainTrail,
    final Reflective beanRefl
  ) {
    return new Mapper<>(
      iso,
      sourceClass,
      targetClass,
      patchByTargetField,
      null,
      null,
      null,
      null,
      explainTrail,
      null,
      null,
      beanRefl
    );
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> A mapper between two sealed interfaces: {@code
   * iso} dispatches on the case, and {@code cases} holds each case's own mapper, which {@link
   * #patch} and {@link #into} dispatch to the same way. Fenced like {@link #ofIso}.
   */
  @SuppressWarnings("exports")
  public static <A, B> Mapper<A, B> ofCases(
    final Iso<A, B> iso,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final List<CaseEntry> cases,
    final List<OpticNode> explainTrail
  ) {
    return new Mapper<>(
      iso,
      sourceClass,
      targetClass,
      Map.of(),
      null,
      null,
      null,
      null,
      explainTrail,
      cases,
      null,
      null
    );
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> A mapper whose {@link #patch} is {@code patcher}
   * whole rather than an overlay field by field: a pair converted through its generated bridge,
   * whose patch is the bridge's own, or a pair with no fields to overlay, such as two enums. {@code
   * intoNames} are the target properties {@link #into} writes. Fenced like {@link #ofIso}.
   */
  @SuppressWarnings("exports")
  public static <A, B> Mapper<A, B> ofPatcher(
    final Iso<A, B> iso,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final BiFunction<A, B, A> patcher,
    final List<String> intoNames
  ) {
    return ofPatcher(iso, sourceClass, targetClass, patcher, intoNames, List.of());
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> A mapper over a generated bridge that converts
   * forward only, as one {@code Telescope.merge} builds: {@code forward} and {@code backward} call
   * {@code bridge} directly, {@link #patch} and {@link #into} refuse as they do on every mapper a
   * merge builds, and {@link #explain()} reports {@code explainTrail}.
   */
  public static <A, B> Mapper<A, B> ofGeneratedForwardOnly(
    final BridgeFn<A, B> bridge,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final List<OpticNode> explainTrail
  ) {
    return new Mapper<>(
      new GeneratedIso<>(bridge),
      sourceClass,
      targetClass,
      null,
      null,
      null,
      null,
      null,
      explainTrail,
      null,
      null,
      null
    );
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> A mapper over a generated bridge: {@code forward}
   * and {@code backward} call {@code bridge} directly, {@link #patch} is {@code patcher}, and
   * {@link #explain()} reports {@code explainTrail}.
   */
  public static <A, B> Mapper<A, B> ofGenerated(
    final BridgeFn<A, B> bridge,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final BiFunction<A, B, A> patcher,
    final List<String> intoNames,
    final List<OpticNode> explainTrail
  ) {
    return ofPatcher(new GeneratedIso<>(bridge), sourceClass, targetClass, patcher, intoNames, explainTrail);
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> As {@link #ofPatcher(Iso, Class, Class,
   * BiFunction, List)}, with the trail {@link #explain()} reports. Fenced like {@link #ofIso}.
   */
  @SuppressWarnings("exports")
  public static <A, B> Mapper<A, B> ofPatcher(
    final Iso<A, B> iso,
    final Class<A> sourceClass,
    final Class<B> targetClass,
    final BiFunction<A, B, A> patcher,
    final List<String> intoNames,
    final List<OpticNode> explainTrail
  ) {
    final var table = new LinkedHashMap<String, PatchEntry>();
    // into reads only the keys; patch goes to the patcher whole and never reads an entry.
    for (final var name : intoNames) table.put(name, new PatchEntry(name, Function.identity()));
    return new Mapper<>(
      iso,
      sourceClass,
      targetClass,
      table,
      null,
      null,
      null,
      null,
      explainTrail,
      null,
      patcher,
      null
    );
  }

  /**
   * Describe what this mapper does, as a queryable {@link OpticReport} — the field correspondences
   * it resolved, the transformations it applies, and the target fields it skips (with reasons). The
   * report is built from the same pairing decisions the mapper uses to convert, so it cannot drift
   * from the mapper's actual behaviour.
   *
   * <pre>{@code
   * // completeness test — a strict mapper skips nothing by construction
   * assertThat(mapper.explain().skipped()).isEmpty();
   * }</pre>
   *
   * @return the structure of this mapper's forward conversion; never null
   */
  public final OpticReport explain() {
    return new OpticReport(explainTrail);
  }

  /**
   * Run this mapper against {@code input} and show, per resolved field, the source value flowing to
   * the target value — a {@link Trace} with the value column filled in. Where {@link #explain()} is
   * the static field structure, {@code trace} runs the conversion and reports what each field
   * became. Off the hot path; a debugging aid.
   *
   * @param input the source value to convert and trace
   * @return the per-field value trace; never null
   */
  public final Trace trace(final A input) {
    return MappingTraces.of(input, forward(input), explainTrail);
  }

  /**
   * Convert forward, {@code A → B}.
   *
   * <pre>{@code
   * final var mapper = Telescope.mapper(UserEntity.class, UserDto.class, to(UserEntity::name, UserDto::fullName));
   * final UserDto dto = mapper.read(entity);
   * }</pre>
   *
   * For the reverse direction, or to thread the conversion through a longer path, use {@link
   * #asTelescope()} (which exposes {@code set}/{@code update}/{@code then}); for a sparse overlay,
   * use {@link #patch}.
   */
  public final B read(final A a) {
    return forward(a);
  }

  /**
   * The mapper as a composable {@code Telescope<A, B>}, for threading the conversion through longer
   * paths via {@link Telescope#then}.
   *
   * <pre>{@code
   * Telescope.of(EntityPage.class)
   *     .each(EntityPage::items)
   *     .then(userMapper.asTelescope())
   *     .field(UserDto::email)
   *     .update(page, String::toLowerCase);
   * }</pre>
   */
  public final Telescope<A, B> asTelescope() {
    // Route through this::forward / this::backward (NOT iso::to / iso::from) so the four hook
    // fields (beforeForward / afterForward / beforeBackward / afterBackward) compose into the
    // returned Telescope. Calling iso::to directly bypasses the hook chain — a configured mapper
    // would silently drop its hooks when handed to longer .then(...) chains via this method.
    return Telescope.iso(this::forward, this::backward);
  }

  /**
   * The mapper's source class — exposed so the deep-mapping engine can decide whether to lift this
   * mapper through a container shape ({@code List} / {@code Set} / {@code Optional} / {@code Map})
   * when the user passed it as the {@code via(...)} element mapper.
   */
  public final Class<A> sourceClass() {
    return sourceClass;
  }

  /** The mapper's target class. See {@link #sourceClass()}. */
  public final Class<B> targetClass() {
    return targetClass;
  }

  /**
   * Project this bidirectional mapper to a {@link ForwardMapper} that exposes only the forward
   * direction. Useful when threading a bidirectional `Mapper` into a forward-only API surface (CDI
   * bean wiring, Spring controllers, audit projections) without the call site needing the
   * `backward()` half.
   *
   * <p><b>Hooks are carried over.</b> The projection routes through {@code this::forward}, which
   * runs the configured {@link #beforeForward}/{@link #afterForward} chain just like the
   * bidirectional surface does. Mirrors {@link #asTelescope()} — a configured mapper would silently
   * drop its hooks if the projection bypassed the chain.
   *
   * <pre>{@code
   * Mapper<UserEntity, UserDto> bidi = Telescope.mapper(UserEntity.class, UserDto.class, ...)
   *     .afterForward(dto -> { dto.setStamp("audited"); return dto; });
   * ForwardMapper<UserEntity, UserDto> projector = bidi.toForwardMapper();
   * // projector.forward(entity) runs the afterForward stamp, same as bidi.forward(entity).
   * }</pre>
   */
  public final ForwardMapper<A, B> toForwardMapper() {
    return ForwardMapper.create(this::forward, sourceClass, targetClass);
  }

  /**
   * Sparse update: overlay the non-null fields of {@code partial} (a partially-populated target)
   * onto {@code base}, leaving the rest of {@code base} untouched. Each present target field is run
   * back through its component {@link Iso}'s backward direction and used to override the matching
   * component when {@code base} is reconstructed via the source {@link Reflective}.
   *
   * <pre>{@code
   * // dtoPatch has a new email, null everything else — only the email changes on the entity:
   * UserEntity updated = userMapper.patch(entity, dtoPatch);
   * }</pre>
   *
   * <p>Records: rebuilds via the canonical constructor with the patched values substituted in.
   * Beans: rebuilds through the strategy a {@code writeBean} row keyed to the source's class or a
   * {@code writeBeans} default names, as backward does, and otherwise through the auto-detected
   * write strategy (builder, then name-matched all-args constructor, then no-arg + setters). The
   * patch table is populated by the deep factory at the top level of the source/target type pair
   * only — patches that target nested components write the <em>whole</em> nested value (since
   * that's what the top-level component holds), not individual sub-component overlays.
   *
   * <p>The result is always a new object, never {@code base} itself, even when {@code partial} is
   * null or every field it holds is null: a caller that mutates the result leaves {@code base} as
   * it was. A {@code @Bridge}'s generated {@code patch} makes the same guarantee. The rebuild is
   * shallow: a component the partial leaves null is handed across from {@code base} as it is, so a
   * nested object or a container it holds is the one {@code base} holds, and changing it changes
   * {@code base}.
   *
   * <p>{@link #patchInto} writes the same overlay into {@code base} in place instead, which is the
   * update a managed entity needs.
   *
   * <p>Between two sealed interfaces, {@code base} is patched by the mapper of its own case. A
   * {@code partial} of the case {@code base}'s case converts to overlays it there; a partial of any
   * other case, or null, is ignored and {@code base} is rebuilt as it is, since overlaying one case
   * onto another would change which case the value is. Call {@link #backward} to change the case. A
   * case converted through its own {@code @Bridge} is patched by that bridge's {@code patch}.
   *
   * @return a new {@code A}, or {@code null} when {@code base} is null
   * @throws UnsupportedOperationException if the mapper came from {@code Telescope.merge} or from
   *     one of the {@code lift*} methods, neither of which has fields to overlay, or if {@code A}
   *     is an enum or a class no write strategy can build, such as an interface that is not sealed
   *     or an abstract class without a static {@code builder()}
   */
  @SuppressWarnings("unchecked")
  public A patch(final A base, final B partial) {
    // A null table means the producing factory does not support patching at all; an EMPTY table
    // means a legitimate mapper with no patchable rows, whose patch is a rebuild of base.
    if (patchByTargetField == null) throw new UnsupportedOperationException(UNSUPPORTED.formatted("patch()"));
    if (base == null) return null;
    if (cases != null) {
      final var entry = caseOfSource(base);
      final var sameCase = entry.targetCase().isInstance(partial) ? partial : null;
      return (A) ((Mapper<Object, Object>) entry.mapper()).patch(base, sameCase);
    }
    if (patcher != null) return patcher.apply(base, partial);
    if (!rebuildable()) throw new UnsupportedOperationException(
      "patch() rebuilds base field by field, and " +
        sourceClass.getName() +
        " is an enum or a class no write strategy can build, so there is nothing to rebuild."
    );
    return (A) overlaid(base, partial, null);
  }

  /**
   * {@link #patch}'s rebuild of {@code base} with {@code partial} laid over it. Where {@code sent}
   * is not null it is given, in the table's order, every property of {@code A} the partial supplies
   * a value for, which {@link #patchInto} writes.
   */
  private Object overlaid(final A base, final B partial, final Set<String> sent) {
    // The overlay map exists only once the partial supplies a value, so a partial that overlays
    // nothing rebuilds base without allocating it.
    HashMap<String, Object> patched = null;
    // A value a source path fills is written back through the path once the rest is rebuilt, in
    // the table's order.
    List<Map.Entry<PatchEntry, Object>> throughPaths = null;
    if (partial != null) for (final var entry : patchByTargetField.entrySet()) {
      final var partialValue =
        entry.getValue().read() != null
          ? entry.getValue().read().apply(partial)
          : targetRefl.read(partial, entry.getKey());
      if (partialValue == null) continue;
      if (entry.getValue().overlay() != null) {
        if (throughPaths == null) throughPaths = new ArrayList<>();
        throughPaths.add(Map.entry(entry.getValue(), partialValue));
      }
      if (entry.getValue().sourceField() == null) continue;
      if (patched == null) patched = new HashMap<>();
      patched.put(entry.getValue().sourceField(), entry.getValue().backward().apply(partialValue));
      if (sent != null) {
        sent.add(entry.getValue().sourceField());
      }
    }
    final var overlay = patched;
    Object rebuilt =
      overlay == null
        ? sourceRefl.construct(sourceClass, name -> sourceRefl.read(base, name))
        : sourceRefl.construct(sourceClass, name ->
            overlay.containsKey(name) ? overlay.get(name) : sourceRefl.read(base, name)
          );
    if (throughPaths != null) for (final var path : throughPaths) {
      rebuilt = path.getKey().overlay().apply(rebuilt, path.getValue());
    }
    return rebuilt;
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> The patcher of a pair converted through the
   * generated bridge {@code fn}: its {@code patch}, with the bridge kept so {@link #patchInto}
   * writes through the bridge's own {@code patchInto}.
   */
  public static <A, B> BiFunction<A, B, A> bridgePatcher(final BridgeFn<A, B> fn) {
    return new BridgePatcher<>(fn);
  }

  /** A generated bridge's {@code patch} as a patcher, carrying the bridge. */
  private record BridgePatcher<A, B>(BridgeFn<A, B> fn) implements BiFunction<A, B, A> {
    @Override
    public A apply(final A base, final B partial) {
      return fn.patch(base, partial);
    }
  }


  /**
   * In-place partial update: write into {@code target} the properties {@link #patch} would overlay
   * from {@code partial}, and only those, keeping {@code target}'s identity. Which properties are
   * written, and the value each takes, is exactly what {@code patch(target, partial)} overlays; see
   * {@link #patch}. A property the partial leaves null is not sent, so it keeps the value {@code
   * target} has, and a nested object the partial carries replaces {@code target}'s whole. How each
   * is written is exactly how {@link #into} writes: through the same setters and adders, a {@code
   * Collection} or {@code Map} refilled in the container {@code target} holds through {@link
   * ContainerCopy}, so it keeps its identity, container properties first, and every value read
   * before the first write.
   *
   * <pre>{@code
   * final OrderEntity managed = repository.findById(id).orElseThrow();
   * mapper.patchInto(managed, changes); // only the fields the client sent, in place
   * }</pre>
   *
   * <p>A null {@code partial}, or one that supplies nothing, leaves {@code target} as it was.
   * Between two sealed interfaces {@code target} is written by the mapper of its own case, and a
   * partial of another case is ignored, as {@code patch} ignores it. A {@code @Bridge} field's
   * mapper hands a target of exactly the class its bridge rebuilds to the bridge's own {@code
   * patchInto}.
   *
   * @param target the existing instance to update; must not be null and must not be a record
   * @param partial a partially populated {@code B} whose non-null fields are written
   * @return {@code target}
   * @throws NullPointerException if {@code target} is null
   * @throws UnsupportedOperationException if {@code target}'s class is a record, if the mapper has
   *     no fields to overlay, as one from {@code Telescope.merge} or a {@code lift*} method, or if
   *     a row of the mapper writes through a source path, which names no property of {@code A}
   * @throws IllegalArgumentException if {@code target}'s class has no setter for a property the
   *     partial supplies other than a container, or if nothing can write a container property it
   *     supplies
   */
  @SuppressWarnings("unchecked")
  public A patchInto(final A target, final B partial) {
    if (target == null) throw new NullPointerException("target");
    if (patchByTargetField == null) throw new UnsupportedOperationException(UNSUPPORTED.formatted("patchInto()"));
    if (cases != null) {
      final var entry = caseOfSource(target);
      if (!entry.targetCase().isInstance(partial)) {
        return target;
      }
      return (A) ((Mapper<Object, Object>) entry.mapper()).patchInto(target, partial);
    }
    refusePatchIntoRecord(target.getClass());
    if (partial == null) {
      return target;
    }
    for (final var entry : patchByTargetField.values()) {
      if (entry.overlay() != null) {
        throw new UnsupportedOperationException(PairingMessages.patchIntoThroughPath(sourceClass.getSimpleName()));
      }
    }
    if (patcher instanceof BridgePatcher<A, B> bridged) {
      // The bridge binds each setter on the class it rebuilds; a subclass, whose own setters can take
      // a value instead, is written here through the setters of its own class, from the bridge's
      // overlay, as into writes one.
      if (target.getClass() == sourceClass) {
        return bridged.fn().patchInto(target, partial);
      }
      final var names = bridged.fn().patchIntoProperties();
      final var flags = new boolean[names.size()];
      final var produced = bridged.fn().overlaid(target, partial, flags);
      final var sent = new LinkedHashSet<String>();
      for (var i = 0; i < flags.length; i++) {
        if (flags[i]) {
          sent.add(names.get(i));
        }
      }
      writeSent(target, sent, produced, null);
      return target;
    }
    if (patcher != null) throw new UnsupportedOperationException(
      "patchInto(): this mapper patches " +
        sourceClass.getSimpleName() +
        " whole, with no fields a partial could supply, so there is nothing to write in place."
    );
    if (!rebuildable()) throw new UnsupportedOperationException(
      "patchInto() rebuilds the partial over the target to read the values it writes, and " +
        sourceClass.getName() +
        " is an enum or a class no write strategy can build."
    );
    final var adders = patchIntoPlan(Beans.persistentClassOf(target)).adders();
    final var sent = new LinkedHashSet<String>();
    // The value read from is built with no adder of the target class run on it, so each adder runs
    // once per element, on the target, as into does.
    final Object produced;
    final Map<String, Object> deferred;
    if (adders.isEmpty()) {
      produced = overlaid(target, partial, sent);
      deferred = null;
    } else {
      final var built = AdderCalls.deferring(sourceClass, () -> overlaid(target, partial, sent));
      produced = built.value();
      deferred = built.elements();
    }
    writeSent(target, sent, produced, deferred);
    return target;
  }

  /** The bound writers of {@link #patchInto} for a target of {@code writeClass}. */
  private PatchIntoPlan patchIntoPlan(final Class<?> writeClass) {
    final var known = patchIntoPlan;
    if (known != null && known.writeClass() == writeClass) {
      return known;
    }
    final var plan = new PatchIntoPlan(writeClass, patchIntoAdders(writeClass), new ConcurrentHashMap<>());
    patchIntoPlan = plan;
    return plan;
  }

  /**
   * Writes into {@code target} each property in {@code sent}, the value {@code produced}, the
   * overlay, holds for it, as {@link #into} writes: an adder-filled collection from {@code
   * deferred} where the overlay was built with its adders deferred.
   */
  private void writeSent(
    final A target,
    final Set<String> sent,
    final Object produced,
    final Map<String, Object> deferred
  ) {
    if (sent.isEmpty()) {
      return;
    }
    final var writeClass = Beans.persistentClassOf(target);
    final var plan = patchIntoPlan(writeClass);
    final var adders = plan.adders();
    final var readClass = Beans.persistentClassOf(produced);
    final var slots = new IntoSlot[sent.size()];
    final var staged = new Object[slots.length];
    var i = 0;
    for (final var name : sent) {
      // A slot refused for a class with no setter is not kept, so it is refused again on each call
      // that sends the property, and never on one that leaves it out.
      final var slot =
        readClass == writeClass
          ? plan
              .slots()
              .computeIfAbsent(name, n -> intoSlot(Beans.capturedReader(readClass, n), writeClass, n, adders, true))
          : intoSlot(Beans.capturedReader(readClass, name), writeClass, name, adders, true);
      slots[i] = slot;
      staged[i] = slot.adder() != null && deferred != null ? deferred.get(name) : slot.read().apply(produced);
      i++;
    }
    writeSlots(target, writeClass, slots, staged);
  }

  private void refusePatchIntoRecord(final Class<?> written) {
    if (written.isRecord() || sourceClass.isRecord()) throw new UnsupportedOperationException(
      PairingMessages.intoRecordTarget(
        sourceClass.getSimpleName(),
        targetClass.getSimpleName(),
        PairingMessages.className(written)
      )
    );
  }

  /**
   * The adder each collection property of a {@code writeClass} target of {@link #patchInto} is
   * filled through, where the mapper builds {@code A} through the adders strategy, and none
   * otherwise.
   */
  private Map<String, BiConsumer<Object, Object>> patchIntoAdders(final Class<?> writeClass) {
    final var writer = sourceRefl.writerFor(sourceClass);
    if (writer == null || Beans.strategyOf(writer) != BeanWriteStrategy.ADDERS) return Map.of();
    return Beans.adders(writeClass);
  }

  /**
   * Whether a rebuild can create an {@code A}: a record through its canonical constructor, a class
   * through the writer {@code construct} would choose. An abstract class or an interface whose
   * static {@code builder()} returns an implementation has such a writer.
   */
  private boolean rebuildable() {
    if (sourceClass.isEnum()) return false;
    return sourceClass.isRecord() || sourceRefl.writerFor(sourceClass) != null;
  }

  /**
   * Forward conversion {@code A → B}. Public so the {@code mapping} sub-package can wrap a {@link
   * Mapper} as an {@link Iso} when stitching deep recursive mappings together (the {@link
   * io.github.eschizoid.telescope.mapping.Mapping#via via(...)} row).
   *
   * <p>A {@code List}, {@code Set} or {@code Map} component whose type is the same on both sides
   * comes back as a shallow copy, so changing the result's container leaves {@code a}'s as it was.
   * The copy behaves like the source and never instantiates a class telescope does not know: a JDK
   * collection is copied into its own class, anything else into the nearest JDK class that keeps
   * its ordering, and failing that into the declared type's default. Unmodifiable inputs are
   * returned as they are. A {@code to(src, tgt, x -> x, x -> x)} row for the component hands the
   * source's container across instead.
   */
  public B forward(final A a) {
    // Null in, null out — matches MapStruct's generated `if (source == null) return null;` and the
    // JPA "no row" idiom adopters rely on. The hook chain is skipped on null since neither
    // pre/postForward has a sensible meaning when no value flowed through.
    if (a == null) return null;
    final A a1 = preForward == null ? a : preForward.apply(a);
    // Post-hook null guard: a preForward that returns null (e.g. a normalisation hook that maps
    // sentinel-empty to null) must propagate as null, not NPE in iso.to(null). postForward is
    // intentionally NOT invoked on this path — matches the top-level null-input behaviour where
    // both pre and post hooks are skipped.
    if (a1 == null) return null;
    final B b = iso.to(a1);
    final B result = postForward == null ? b : postForward.apply(a1, b);
    // Value trace at TRACE, from the already-computed result (never re-running forward, which would
    // recurse). Gate with isLoggable BEFORE building the Supplier so the hot path is
    // allocation-free
    // when off, and skip trail-less shells (their trace is empty). Both checks are cheap.
    if (traced && logger.isLoggable(Level.TRACE)) logger.log(Level.TRACE, () ->
      MappingTraces.of(a, result, explainTrail).toString()
    );
    return result;
  }

  /**
   * In-place update: mutate an existing {@code target} with values derived from {@code source},
   * preserving {@code target}'s identity. Closes MapStruct's {@code @MappingTarget} for the bean
   * path — common when wiring a "load entity by ID, update fields, persist" pattern through JPA's
   * managed-entity contract where allocating a fresh instance loses the persistence-context
   * tracking.
   *
   * <pre>{@code
   * final UserEntity managed = repository.findById(id).orElseThrow();
   * userMapper.into(managed, dto);   // writes dto's mapped fields onto `managed` in place
   * repository.save(managed);
   * }</pre>
   *
   * <p><b>What it updates.</b> The properties of {@code target} the mapper writes: each a source
   * property pairs with by name, and each a row fills, such as a rename, a {@code constant} or a
   * {@code compute} row. Each takes the value {@code forward(source)} gives it. Every other
   * property of {@code target}, such as a getter the class derives from others, keeps the value it
   * had. The hook chain (before/after) runs as it does in {@link #forward}; the only difference is
   * the final write step targets {@code target} rather than a fresh allocation.
   *
   * <p><b>Records rejected.</b> Records are immutable; calling {@code into(...)} on a record-target
   * mapper throws {@link UnsupportedOperationException}, naming the record, at apply time. Use
   * {@link #forward(Object)} and discard / replace the receiver, or have the target type be a bean
   * with setters.
   *
   * <p><b>Setter requirement.</b> Each property it updates other than a {@code Collection} or
   * {@code Map} is written through a public setter of {@code target.getClass()} that takes a value
   * of the property's type, whatever strategy {@code forward} builds a new instance with. A bean
   * built by a builder or a constructor may have no such setter for a property the mapper writes;
   * {@code into} then cannot update that property in place and refuses the call by the property's
   * name, before writing anything. The refusal comes from the first {@code into} call for a target
   * of that class, not when the mapper is built, because a mapper used only through {@code forward}
   * needs no setters and a subclass handed to {@code into} may declare the setter its declared
   * class lacks.
   *
   * <p><b>Collections and maps are merged where they can be.</b> A {@code Collection} or {@code
   * Map} the target's getter returns that can be changed is cleared and refilled with the elements
   * {@code forward(source)} gives the property, so it keeps its identity, which an ORM-managed
   * collection with orphan removal needs. One that cannot be changed is replaced through the
   * setter, as is the converted value where the getter returns null: an unmodifiable JDK container
   * ({@code List.of}, {@code Collections.unmodifiable*} and their kin) is known in advance, and any
   * other container whose refill throws, an {@link UnsupportedOperationException} from {@code
   * Arrays.asList} or a {@link NullPointerException} from a sorted set handed a null, has its
   * elements put back, so whoever else holds it sees it as it was, and is then replaced. A null
   * value from the source is written through the setter as null, as {@code forward} gives it. Only
   * a container property nothing can write, one with no setter whose target holds no container or
   * one that cannot be changed, is refused by the property's name, with what the refill threw as
   * the cause. Container properties are written before every other property, those with no setter
   * first, and an unmodifiable JDK container with no setter is refused before anything is written,
   * so a refused call leaves the target's other properties as they were. The refill goes through
   * {@link ContainerCopy}, whose {@link ContainerCopy.Policy} a module can replace.
   *
   * <p><b>Adders.</b> Where the mapper builds the target class through {@code writeBean(...,
   * ADDERS)} or {@code writeBeans(ADDERS)}, each collection a field of its name stores is cleared
   * and then filled one element at a time through its adder ({@code addLine} for {@code lines}),
   * which needs no setter for it. The value {@code forward} builds for {@code into} to read from
   * runs none of its adders, so each adder runs exactly once per element, on {@code target}, and an
   * adder that changes or drops an element leaves {@code target} as {@code forward} would build it.
   * An adder that rejects an element by throwing has its exception thrown on, once the collection
   * the target holds has its elements back; the setter never writes the collection past it.
   *
   * <p><b>Return value for chaining.</b> Returns {@code target} (the same reference passed in) so
   * call sites can fluently chain ({@code repository.save(mapper.into(managed, dto))}).
   *
   * <p><b>Sealed interfaces.</b> Between two sealed interfaces, {@code target} is written by the
   * mapper of its own case, and {@code source} must convert to that case.
   *
   * <p><b>A {@code @Bridge} field's mapper.</b> Where the bridge generated for the field can update
   * its target in place, the field's mapper hands a target of exactly the class the bridge writes
   * to the bridge's {@code into}, which writes the same properties through the same setters with no
   * reflection, and merges collections and maps the same way through the same {@link ContainerCopy}
   * methods; {@link #explain()} says so. A subclass of that class is written here instead, since
   * its own setters, which the bridge cannot know, decide how it takes a value. The bridge has such
   * an {@code into} only when the target is a bean with a setter for every property the bridge
   * writes other than a container.
   *
   * @param target the existing instance to mutate; must not be null and must not be a record
   * @param source the source value to map from
   * @return {@code target} (same reference, mutated in place)
   * @throws NullPointerException if {@code target} or {@code source} is null
   * @throws UnsupportedOperationException if {@code target}'s class is a record
   * @throws IllegalArgumentException if {@code target}'s class has no setter for a property the
   *     mapper writes other than a container, if nothing can write a container property, or,
   *     between two sealed interfaces, if {@code source} converts to a case other than {@code
   *     target}'s
   */
  public B into(final B target, final A source) {
    if (target == null) throw new NullPointerException("target");
    if (source == null) throw new NullPointerException("source");
    if (cases == null) refuseRecordTarget(targetClass);
    // A target filled through its adders is converted with no adder run on the value forward
    // builds, so each adder runs once per element, on the target. Between two sealed interfaces
    // the mapper that writes the target is its case's.
    final var writer = writerOf(target);
    if (writer != null && !writer.intoAdders(Beans.persistentClassOf(target)).isEmpty()) {
      final var deferred = AdderCalls.deferring(writer.targetClass, () -> forward(source));
      return writeInto(target, deferred.value(), deferred.elements());
    }
    return writeInto(target, forward(source), null);
  }

  /**
   * The mapper that writes {@code target} in place: this one, or between two sealed interfaces the
   * mapper of the case {@code target} is; null where that mapper has no table to write through,
   * which {@link #writeInto} refuses.
   */
  private Mapper<?, ?> writerOf(final Object target) {
    if (cases == null) return patchByTargetField == null ? null : this;
    for (final var entry : cases) {
      if (entry.targetCase().isInstance(target)) return entry.mapper().writerOf(target);
    }
    return null;
  }

  private void refuseRecordTarget(final Class<?> written) {
    if (written.isRecord()) throw new UnsupportedOperationException(
      PairingMessages.intoRecordTarget(
        sourceClass.getSimpleName(),
        targetClass.getSimpleName(),
        PairingMessages.className(written)
      )
    );
  }

  /**
   * Write {@code produced}, which this mapper's forward made, onto {@code target} in place. {@code
   * deferred} holds, by property, the collection each adder of {@code produced} was to be handed,
   * where {@code produced} was built with its adders deferred, and is null where it was not, in
   * which case an adder-filled collection is read from {@code produced} itself.
   */
  @SuppressWarnings("unchecked")
  private B writeInto(final B target, final B produced, final Map<String, Object> deferred) {
    if (cases != null) {
      final var entry = caseOfTarget(target);
      if (produced != null && !entry.targetCase().isInstance(produced)) throw new IllegalArgumentException(
        "Mapper.into(target, source): the source converts to " +
          PairingMessages.className(produced.getClass()) +
          ", and the target is a " +
          PairingMessages.className(target.getClass()) +
          ". A value is written into a target of the case it converts to; call forward(source) to" +
          " change the case."
      );
      refuseRecordTarget(entry.targetCase());
      return (B) ((Mapper<Object, Object>) entry.mapper()).writeInto(target, produced, deferred);
    }
    // Two-phase apply for atomicity AND scoping.
    //
    // Atomicity: stage every (name, value) read BEFORE any setter runs on target. If a stage-time
    // read throws, target is untouched. If a write-time setter throws midway, the staging map is
    // already fully populated; partial writes on a managed entity are at most the slot range that
    // already drained, which is strictly smaller than the all-or-nothing on the raw-loop version.
    //
    // Scoping: iterate the mapper's patch-table keyset — the set of top-level target fields the
    // engine actually produced values for — NOT every getter-derived property on target.getClass().
    // Iterating all properties would (1) clobber unmapped pre-existing fields on the managed
    // entity, defeating the "load-mutate-save" idiom; and (2) refuse a getter the class derives
    // from others (e.g. getFullName() from firstName + lastName), which has no setter and which
    // the mapper never writes.
    // Same null-table sentinel as patch(): the producing factory does not support target-mutating
    // operations at all — throw the UOE, don't NPE on the sentinel.
    if (patchByTargetField == null) throw new UnsupportedOperationException(UNSUPPORTED.formatted("into()"));
    // Writers bind to the target's persistent class, not the declared one: a mapper declared
    // against a base or @MappedSuperclass type is routinely handed a concrete subclass, and the
    // setter that matters may exist only there. persistentClassOf collapses a proxy back to its
    // entity class so the cache stays one entry per real class.
    final var writeClass = Beans.persistentClassOf(target);
    if (produced == null) {
      // preForward and postForward may map to null by contract, and a null product wrote null to
      // every mapped property rather than failing. Readers are never bound here: there is nothing
      // to read from.
      final var names = intoNames();
      final var adders = intoAdders(writeClass);
      final var slots = new IntoSlot[names.size()];
      var i = 0;
      for (final var name : names) slots[i++] = intoSlot(null, writeClass, name, adders);
      return writeSlots(target, writeClass, slots, new Object[slots.length]);
    }
    final var slots = intoSlots(Beans.persistentClassOf(produced), writeClass);
    final var staged = new Object[slots.length];
    for (var i = 0; i < slots.length; i++) {
      final var slot = slots[i];
      staged[i] = slot.adder() != null && deferred != null ? deferred.get(slot.name()) : slot.read().apply(produced);
    }
    return writeSlots(target, writeClass, slots, staged);
  }

  /**
   * Write each of {@code staged}, the values {@code forward} gave the properties {@code slots}
   * name, into {@code target}, a container property as {@link ContainerCopy} decides for both
   * paths: refilled in the container the target holds where that can be changed, so it keeps its
   * identity, through the adder under the adders strategy, and otherwise written through the
   * setter. Every container the target holds is read, and one nothing can write refused by name,
   * before the first write. Container properties are written before every other property, those
   * with no setter first, so a container found unchangeable only when it is written leaves every
   * other property as it was.
   */
  @SuppressWarnings("unchecked")
  private <T> T writeSlots(final T target, final Class<?> writeClass, final IntoSlot[] slots, final Object[] staged) {
    final var source = sourceClass.getSimpleName();
    final var targetName = targetClass.getSimpleName();
    final var held = new Object[slots.length];
    for (var i = 0; i < slots.length; i++) {
      final var slot = slots[i];
      if (slot.held() == null) continue;
      held[i] = slot.held().apply(target);
      ContainerCopy.refuseUnwritable(
        held[i],
        staged[i],
        slot.write() != null,
        slot.adder() != null,
        source,
        targetName,
        PairingMessages.className(writeClass),
        slot.name()
      );
    }
    for (var pass = 0; pass < 3; pass++) {
      for (var i = 0; i < slots.length; i++) {
        final var slot = slots[i];
        final var order = slot.held() == null ? 2 : slot.write() == null ? 0 : 1;
        if (order != pass) continue;
        if (order == 2) {
          slot.write().accept(target, staged[i]);
          continue;
        }
        final var written =
          slot.adder() != null
            ? ContainerCopy.fill(
                source,
                targetName,
                target,
                slot.name(),
                held[i],
                (Collection<Object>) staged[i],
                slot.adder(),
                slot.write() != null
              )
            : ContainerCopy.refill(source, targetName, target, slot.name(), held[i], staged[i], slot.write() != null);
        if (!written) slot.write().accept(target, staged[i]);
      }
    }
    return target;
  }

  /**
   * The adder each collection property of a {@code writeClass} target is filled through, where the
   * mapper builds its target class through the adders strategy, and none otherwise.
   */
  private Map<String, BiConsumer<Object, Object>> intoAdders(final Class<?> writeClass) {
    final var writer = targetRefl.writerFor(targetClass);
    if (writer == null || Beans.strategyOf(writer) != BeanWriteStrategy.ADDERS) return Map.of();
    return Beans.adders(writeClass);
  }

  /**
   * The accessors {@link #into} writes property {@code name} of a {@code writeClass} target with:
   * for a container it refills in place, its getter, its adder where {@code adders} has one, and
   * its setter where the class has one; for any other property its setter, which it has to have.
   */
  private IntoSlot intoSlot(
    final Function<Object, Object> read,
    final Class<?> writeClass,
    final String name,
    final Map<String, BiConsumer<Object, Object>> adders
  ) {
    return intoSlot(read, writeClass, name, adders, false);
  }

  /** As above, refusing a property with no setter in {@link #patchInto}'s words where it writes. */
  private IntoSlot intoSlot(
    final Function<Object, Object> read,
    final Class<?> writeClass,
    final String name,
    final Map<String, BiConsumer<Object, Object>> adders,
    final boolean patchInto
  ) {
    final var adder = adders.get(name);
    if (adder != null || Beans.refilledInPlace(writeClass, name)) return new IntoSlot(
      name,
      read,
      Beans.capturedWriter(writeClass, name),
      Beans.capturedReader(writeClass, name),
      adder
    );
    return new IntoSlot(name, read, intoWriter(writeClass, name, patchInto), null, null);
  }

  /** The case entry {@code value}, a value of the sealed source, belongs to. */
  private CaseEntry caseOfSource(final Object value) {
    for (final var entry : cases) if (entry.sourceCase().isInstance(value)) return entry;
    throw new IllegalStateException(
      "Mapper: " + value.getClass().getName() + " is no case of sealed " + sourceClass.getName()
    );
  }

  /** The case entry {@code value}, a value of the sealed target, belongs to. */
  private CaseEntry caseOfTarget(final Object value) {
    for (final var entry : cases) if (entry.targetCase().isInstance(value)) return entry;
    throw new IllegalStateException(
      "Mapper: " + value.getClass().getName() + " is no case of sealed " + targetClass.getName()
    );
  }

  /**
   * One target property's bound accessors, resolved once per mapper: the reader of {@code
   * forward}'s result, the setter, and for a container {@code into} refills in place the reader of
   * the container the target holds. A container may have no setter, and under the adders strategy a
   * collection has its adder.
   */
  private record IntoSlot(
    String name,
    Function<Object, Object> read,
    BiConsumer<Object, Object> write,
    Function<Object, Object> held,
    BiConsumer<Object, Object> adder
  ) {}

  /** The target class {@link #patchInto}'s bound slots and adders are valid for. */
  private record PatchIntoPlan(
    Class<?> writeClass,
    Map<String, BiConsumer<Object, Object>> adders,
    ConcurrentHashMap<String, IntoSlot> slots
  ) {}

  /** The class pair a bound slot array is valid for, alongside the array. */
  private record IntoPlan(Class<?> readClass, Class<?> writeClass, IntoSlot[] slots) {}

  /**
   * The target properties {@code into} writes: every key of the patch table but those of entries
   * that read the partial through a target path, which name no property of the target.
   */
  private List<String> intoNames() {
    final var names = new ArrayList<String>(patchByTargetField.size());
    for (final var entry : patchByTargetField.entrySet())
      if (entry.getValue().read() == null) names.add(entry.getKey());
    return names;
  }

  /**
   * The bound accessor pair for every property {@link #into} writes. The patch table's keys are
   * fixed for the life of the mapper, so the only thing that can vary between calls is the pair of
   * runtime classes — and in the overwhelming case it does not vary at all. One memoised plan
   * therefore serves every call, and resolving accessors per property per call re-paid two proxy
   * unwraps, two {@code ClassValue} probes and two name lookups for constant work.
   *
   * <p>Resolved on use rather than at construction because a record target reaches construction
   * routinely — {@code Telescope.mapper(Dto.class, SomeRecord.class)} is ordinary — and a record's
   * accessors are not bean getters, so binding eagerly would fail at build time for a mapper whose
   * {@code into} is never called and correctly rejects records anyway.
   *
   * <p>The race between two callers is benign: both build equivalent arrays for the same class
   * pair, and publishing either is correct.
   *
   * <p>One slot assumes a caller passes targets of one concrete class, which is the ordinary shape.
   * A caller alternating across an entity hierarchy misses every time and rebinds per call — still
   * faster than resolving per property per call, but it turns this field into a volatile store on
   * every call, and a mapper is a shared singleton in both framework starters. Key the memo by
   * class if that pattern ever shows up in a profile.
   */
  private IntoSlot[] intoSlots(final Class<?> readClass, final Class<?> writeClass) {
    final var cached = intoPlan;
    if (cached != null && cached.readClass() == readClass && cached.writeClass() == writeClass) return cached.slots();
    final var names = intoNames();
    final var adders = intoAdders(writeClass);
    final var built = new IntoSlot[names.size()];
    var i = 0;
    for (final var name : names) {
      built[i++] = intoSlot(Beans.capturedReader(readClass, name), writeClass, name, adders);
    }
    intoPlan = new IntoPlan(readClass, writeClass, built);
    return built;
  }

  /**
   * The setter {@link #into} writes property {@code name} of a {@code writeClass} target through,
   * refusing by name a property the class has no setter for, which {@code into} cannot update in
   * place. Every writer is bound before the first write, so a refused call leaves the target as it
   * was.
   */
  private BiConsumer<Object, Object> intoWriter(final Class<?> writeClass, final String name, final boolean patchInto) {
    final var writer = Beans.capturedWriter(writeClass, name);
    if (writer == null) throw new IllegalArgumentException(
      patchInto
        ? PairingMessages.patchIntoPropertyUnwritable(
            sourceClass.getSimpleName(),
            targetClass.getSimpleName(),
            PairingMessages.className(writeClass),
            name
          )
        : PairingMessages.intoPropertyUnwritable(
            sourceClass.getSimpleName(),
            targetClass.getSimpleName(),
            PairingMessages.className(writeClass),
            name
          )
    );
    return writer;
  }

  /** Backward conversion {@code B → A}. See {@link #forward(Object)}. */
  public A backward(final B b) {
    // Null in, null out — symmetric with forward(). See the rationale on forward(Object) above.
    if (b == null) return null;
    final B b1 = preBackward == null ? b : preBackward.apply(b);
    if (b1 == null) return null;
    final A a = iso.from(b1);
    return postBackward == null ? a : postBackward.apply(b1, a);
  }

  /**
   * Compose a pre-forward hook: before the structural forward direction reads from {@code A}, run
   * {@code hook} on the source and feed the result into the conversion. MapStruct's
   * {@code @BeforeMapping void prep(Source src)} equivalent — the canonical place for input
   * normalisation, validation, or canonicalisation.
   *
   * <p>The hook is a {@link Function}, not a {@link java.util.function.Consumer}, so it works for
   * both immutable record sources (return a new {@code A} with the hook's changes) and mutable bean
   * sources (mutate {@code a} in place and {@code return a} the same instance).
   *
   * <p>Returns a new {@code Mapper} — chains compose left-to-right.
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .beforeForward(e -> e.normalised());
   * }</pre>
   *
   * @see io.github.eschizoid.telescope.Telescope#before(Function) for the path-level (single-
   *     direction, lattice-native) sibling that composes through {@code .then(...)} chains.
   */
  public final Mapper<A, B> beforeForward(final Function<? super A, ? extends A> hook) {
    final Function<A, A> prev = this.preForward;
    final Function<A, A> next = prev == null ? hook::apply : a -> hook.apply(prev.apply(a));
    return new Mapper<>(
      iso,
      sourceClass,
      targetClass,
      patchByTargetField,
      next,
      postForward,
      preBackward,
      postBackward,
      explainTrail,
      cases,
      patcher,
      beanRefl
    );
  }

  /**
   * Compose a post-forward hook: after the structural forward direction produces a {@code B}, run
   * {@code hook} on it and return whatever the hook returns. Closes MapStruct's
   * {@code @AfterMapping} gap for the common "stamp a derived/computed value after the structural
   * mapping completes" case ({@code entity.setUpdatedAt(Instant.now())} after the row data is in
   * place).
   *
   * <p>The hook is a {@link Function}, not a {@link java.util.function.Consumer}, so it works for
   * both immutable record targets (return a new {@code B} with the hook's changes) and mutable bean
   * targets (mutate {@code b} in place and {@code return b} the same instance).
   *
   * <p>Returns a new {@code Mapper} — chains compose left-to-right (the first {@code afterForward}
   * call's hook runs first, the second runs second, etc.). The backward direction is unchanged.
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .afterForward(dto -> dto.withUpdatedAt(Instant.now().toString()));
   * }</pre>
   *
   * @see io.github.eschizoid.telescope.Telescope#after(Function) for the path-level (single-
   *     direction, lattice-native) sibling that composes through {@code .then(...)} chains.
   */
  public final Mapper<A, B> afterForward(final Function<? super B, ? extends B> hook) {
    return afterForward((a, b) -> hook.apply(b));
  }

  /**
   * Source-aware post-forward hook: same as {@link #afterForward(Function)} but the hook receives
   * BOTH the source {@code A} (as seen by the forward direction, after any {@link
   * #beforeForward(Function)} normalisation) AND the structural result {@code B}. MapStruct's
   * {@code @AfterMapping void enrich(Source src, @MappingTarget Dto dto)} equivalent — typed, not
   * reflective.
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .afterForward((src, dto) -> dto.withDisplayName(src.firstName() + " " + src.lastName()));
   * }</pre>
   */
  public final Mapper<A, B> afterForward(final BiFunction<? super A, ? super B, ? extends B> hook) {
    final BiFunction<A, B, B> prev = this.postForward;
    final BiFunction<A, B, B> next = prev == null ? hook::apply : (a, b) -> hook.apply(a, prev.apply(a, b));
    return new Mapper<>(
      iso,
      sourceClass,
      targetClass,
      patchByTargetField,
      preForward,
      next,
      preBackward,
      postBackward,
      explainTrail,
      cases,
      patcher,
      beanRefl
    );
  }

  /**
   * Compose a pre-backward hook: before the structural backward direction consumes a {@code B}, run
   * {@code hook} on it and feed the result into {@code backward}. The MapStruct
   * {@code @BeforeMapping} equivalent for the backward direction.
   *
   * <p>Returns a new {@code Mapper} — chains compose left-to-right (the first {@code
   * beforeBackward} call's hook runs first on a backward invocation). The forward direction is
   * unchanged.
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .beforeBackward(dto -> dto.normalised());
   * }</pre>
   */
  public final Mapper<A, B> beforeBackward(final Function<? super B, ? extends B> hook) {
    final Function<B, B> prev = this.preBackward;
    final Function<B, B> next = prev == null ? hook::apply : b -> hook.apply(prev.apply(b));
    return new Mapper<>(
      iso,
      sourceClass,
      targetClass,
      patchByTargetField,
      preForward,
      postForward,
      next,
      postBackward,
      explainTrail,
      cases,
      patcher,
      beanRefl
    );
  }

  /**
   * Compose a post-backward hook: after the structural backward direction produces a rebuilt {@code
   * A}, run {@code hook} on it and return whatever the hook returns. The symmetric mirror of {@link
   * #afterForward(Function)} for the backward direction.
   *
   * <p>The canonical place to stamp source-side derived fields after a target → source rebuild
   * (e.g. {@code entity.setLastModifiedBy(currentUser())} during a backward write).
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .afterBackward(e -> e.withLastModifiedAt(Instant.now()));
   * }</pre>
   */
  public final Mapper<A, B> afterBackward(final Function<? super A, ? extends A> hook) {
    return afterBackward((b, a) -> hook.apply(a));
  }

  /**
   * Target-aware post-backward hook: same as {@link #afterBackward(Function)} but the hook receives
   * BOTH the target {@code B} (as seen by the backward direction, after any {@link
   * #beforeBackward(Function)} normalisation) AND the rebuilt source {@code A}. Use when the stamp
   * depends on a target-side field that the source doesn't carry.
   *
   * <pre>{@code
   * Telescope.mapper(Entity.class, Dto.class, ...)
   *     .afterBackward((dto, entity) -> entity.withAuditTrail(dto.actor() + "@" + Instant.now()));
   * }</pre>
   */
  public final Mapper<A, B> afterBackward(final BiFunction<? super B, ? super A, ? extends A> hook) {
    final BiFunction<B, A, A> prev = this.postBackward;
    final BiFunction<B, A, A> next = prev == null ? hook::apply : (b, a) -> hook.apply(b, prev.apply(b, a));
    return new Mapper<>(
      iso,
      sourceClass,
      targetClass,
      patchByTargetField,
      preForward,
      postForward,
      preBackward,
      next,
      explainTrail,
      cases,
      patcher,
      beanRefl
    );
  }

  /**
   * Lift this element-level mapper to a {@code Mapper<List<A>, List<B>>}. Forward maps each element
   * through {@link #forward}; backward maps each element through {@link #backward}. {@code null}
   * lists round-trip to {@code null} (mirrors the null-pass-through convention of {@link
   * io.github.eschizoid.telescope.internal.optics.Iso#liftList}).
   *
   * <p>The lifted mapper's {@link #patch} and {@link #into} throw {@link
   * UnsupportedOperationException}: a list-shaped root has no fields to overlay. Use it as a
   * building block in {@link
   * io.github.eschizoid.telescope.mapping.Mapping#via(io.github.eschizoid.telescope.Telescope.Accessor,
   * io.github.eschizoid.telescope.Telescope.Accessor, Mapper) Mapping.via} or hand-roll the
   * forward/backward calls at a {@code List} call site.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public final Mapper<List<A>, List<B>> liftList() {
    final var lifted = Iso.liftList(iso);
    return new Mapper<>(lifted, (Class) List.class, (Class) List.class, null);
  }

  /** Same as {@link #liftList()} but produces a {@code Mapper<Set<A>, Set<B>>}. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public final Mapper<Set<A>, Set<B>> liftSet() {
    final var lifted = Iso.liftSet(iso);
    return new Mapper<>(lifted, (Class) Set.class, (Class) Set.class, null);
  }

  /** Same as {@link #liftList()} but produces a {@code Mapper<Optional<A>, Optional<B>>}. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public final Mapper<Optional<A>, Optional<B>> liftOptional() {
    final var lifted = Iso.liftOptional(iso);
    return new Mapper<>(lifted, (Class) Optional.class, (Class) Optional.class, null);
  }

  /**
   * Same as {@link #liftList()} but produces a {@code Mapper<Map<K, A>, Map<K, B>>}. Keys are
   * preserved; only the values flow through {@link #forward}/{@link #backward}.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public final <K> Mapper<Map<K, A>, Map<K, B>> liftMapValues() {
    final var lifted = Iso.<K, A, B>liftMapValues(iso);
    return new Mapper<>(lifted, (Class) Map.class, (Class) Map.class, null);
  }
}
