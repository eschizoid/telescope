package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.conversion.BridgeRegistry;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.conversion.Match;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.BridgeHolderProbe;
import io.github.eschizoid.telescope.internal.FixedCheck;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.MhIso;
import io.github.eschizoid.telescope.internal.NativeImage;
import io.github.eschizoid.telescope.internal.NullDefaults;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.ResultCheck;
import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.pairing.ContainerView;
import io.github.eschizoid.telescope.internal.pairing.FixedFit;
import io.github.eschizoid.telescope.internal.pairing.KeptNames;
import io.github.eschizoid.telescope.internal.pairing.OwnBridgeRule;
import io.github.eschizoid.telescope.internal.pairing.PairDecision;
import io.github.eschizoid.telescope.internal.pairing.PairWalk;
import io.github.eschizoid.telescope.internal.pairing.Paired;
import io.github.eschizoid.telescope.internal.pairing.PairedTypes;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import io.github.eschizoid.telescope.internal.pairing.ReflectionProps;
import io.github.eschizoid.telescope.internal.pairing.SourceWrites;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.mapping.Cases;
import io.github.eschizoid.telescope.mapping.Compute;
import io.github.eschizoid.telescope.mapping.Conditional;
import io.github.eschizoid.telescope.mapping.Constant;
import io.github.eschizoid.telescope.mapping.Drop;
import io.github.eschizoid.telescope.mapping.ForwardOnlyTransformTo;
import io.github.eschizoid.telescope.mapping.FromTelescopeTo;
import io.github.eschizoid.telescope.mapping.MapStep;
import io.github.eschizoid.telescope.mapping.Mapping;
import io.github.eschizoid.telescope.mapping.NullHint;
import io.github.eschizoid.telescope.mapping.SameTypedTo;
import io.github.eschizoid.telescope.mapping.TelescopeTo;
import io.github.eschizoid.telescope.mapping.TelescopeToTelescope;
import io.github.eschizoid.telescope.mapping.TypedTransformTo;
import io.github.eschizoid.telescope.mapping.Via;
import io.github.eschizoid.telescope.mapping.ViaClass;
import io.github.eschizoid.telescope.mapping.ViaMethods;
import io.github.eschizoid.telescope.mapping.WriteHint;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Engine for {@link Telescope#map(Class, Class, MapStep...)} / {@link Telescope#mapper(Class,
 * Class, MapStep...)}. Walks the source/target structure pair-by-pair and caches an {@link Iso} per
 * {@code (sourceClass, targetClass)} pair encountered — same-named scalar components identity-link
 * via {@link Iso#identity()}, nested records/beans recurse, and same-kind container components
 * ({@code List<X>↔List<Y>} / {@code Set<X>↔Set<Y>} / {@code Map<K, X>↔Map<K, Y>} / {@code
 * Optional<X>↔Optional<Y>}) lift the inner element {@code Iso} through the container via {@link
 * Iso#liftList}, {@link Iso#liftSet}, {@link Iso#liftMapValues}, {@link Iso#liftOptional}.
 * Containers nest to any depth — the recursion peels one shape per pass and dispatches the rest.
 *
 * <p><b>Lattice-first.</b> The cache value is {@link Iso} directly — the lattice primitive. No
 * intermediate Object-typed plumbing, no parallel link tables. Per-pair assembly composes three
 * Isos via {@code .then(...)}: source-side {@link Reflective#structuralIso} (reversed) decomposes
 * {@code S} into a name-keyed map, a remap step renames keys and applies per-field Isos, and
 * target-side {@link Reflective#structuralIso} builds {@code T} from the remapped map. The
 * structural decomposition is itself an {@link Iso}, so the bidirection falls out by lattice
 * composition without an inline lambda body.
 *
 * <p><b>Records and beans, uniformly.</b> Each side of each type pair picks its {@link Reflective}
 * independently via {@link Reflective#of(Class)}. A single deep-mapping call can mix and match
 * records and POJOs at any depth: top-level bean → record, nested record → bean, whatever shape the
 * user has. The Mapping rows' raw method names (recovered via {@code SerializedLambda}) are
 * normalized per side ({@code User::getName} → {@code "name"} on the bean side; {@code User::name}
 * → {@code "name"} on the record side) so the row factories work for either kind without ceremony.
 *
 * <p><b>Cycle handling.</b> The cache is reserved with a placeholder before recursion descends into
 * a type pair, so re-entry on the same pair (e.g. {@code User} containing {@code Optional<User>})
 * returns immediately. Auto-derived component Isos use {@link Iso#of} with cache-reading bodies, so
 * when the link runs at top-level {@code to}/{@code from} time, the cache always holds the
 * fully-built Iso.
 */
public final class DeepMap {

  private DeepMap() {}

  /**
   * The shared pairing decision spec, adapted to the reflection world. Every per-pair conversion
   * decision and every pairing diagnostic routes through this one rule set — the compile-time
   * verifier consumes the same rules through a {@code TypeMirror} adapter, so what constructs here
   * and what compiles there cannot drift.
   */
  private static final ReflectionProps PROPS = new ReflectionProps();

  static final PairingRules<Type> PAIRING = new PairingRules<>(PROPS);

  /** The shared walk of the pairs a mapper visits, which the compile-time verifier takes too. */
  private static final PairWalk<Type> PAIR_WALK = new PairWalk<>(new ReflectionProps());

  // ---------- Package-private entries (called from Telescope.map / Telescope.mapper) ----------

  static <A, B> Iso<A, B> resolve(final Class<A> source, final Class<B> target, final MapStep[] steps) {
    return resolution(source, target, steps, false).iso;
  }

  /**
   * Deep-conversion resolution that also carries the introspection trail, so the {@code
   * Telescope<A, B>} returned by {@code Telescope.map} answers {@code explain()} with the field
   * rows it resolved.
   */
  static <A, B> Telescope<A, B> resolveMapped(final Class<A> source, final Class<B> target, final MapStep[] steps) {
    final var r = resolution(source, target, steps, false);
    return Telescope.mapped(r.iso, r.trail);
  }

  /**
   * What a mapper factory called from {@code caller} for the pair resolves to: the provider of the
   * bridge a {@code @Bridge} field of {@code caller} declares, which the factory dispatches to, and
   * the node {@code explain()} reports for the path the mapper runs on. A pair no field of {@code
   * caller} is known to declare has neither.
   */
  record Declaration(BridgeProvider provider, OpticNode.Declared node) {
    static final Declaration NONE = new Declaration(null, null);
  }

  /**
   * The {@link Declaration} for a factory called from {@code caller}. A provider generated from
   * rows of other kinds than {@code steps} is passed over, and the runtime mapper is built from the
   * rows instead, since the bridge was generated from a different declaration: the processor
   * stopped running on the class and an older bridge stayed on the class path.
   */
  static Declaration declaration(
    final Class<?> caller,
    final Class<?> source,
    final Class<?> target,
    final MapStep[] steps
  ) {
    if (caller == null) return Declaration.NONE;
    final var provider = BridgeRegistry.declaredBy(caller, source, target).orElse(null);
    if (provider == null) {
      if (!UNREAD_ROWS.get(caller).contains(source.getName() + " -> " + target.getName())) return Declaration.NONE;
      return new Declaration(
        null,
        new OpticNode.Declared(
          caller.getName(),
          OpticNode.Runs.RUNTIME,
          caller.getName() +
            " was compiled without the javac tree API, so the rows of its @Bridge fields were not" +
            " compiled into a bridge"
        )
      );
    }
    final var given = new ArrayList<String>(steps.length);
    for (final var step : steps) given.add(step.getClass().getSimpleName());
    final var stale = staleness(provider, steps, given);
    if (stale != null) {
      final var why =
        "the bridge on the class path was generated from " +
        stale +
        "; re-run the annotation processor on " +
        caller.getName();
      LOGGER.log(
        Level.WARNING,
        () ->
          "The @Bridge mapper of " +
          PairingMessages.className(source) +
          " -> " +
          PairingMessages.className(target) +
          " in " +
          caller.getName() +
          " runs on the runtime path: " +
          why +
          "."
      );
      return new Declaration(null, new OpticNode.Declared(caller.getName(), OpticNode.Runs.RUNTIME, why));
    }
    final var bridgeName = provider.getClass().getName().replaceFirst("Provider$", "");
    return new Declaration(
      provider,
      new OpticNode.Declared(
        caller.getName(),
        OpticNode.Runs.GENERATED,
        "the bridge " + bridgeName + ", generated from the rows of its @Bridge field"
      )
    );
  }

  /**
   * How the rows a provider's bridge was generated from differ from {@code steps}, or null when
   * they agree. The kinds of the rows are compared everywhere. The accessors each row names, and the
   * properties a source path steps through, are
   * compared on the JVM, where reading them decodes each method reference; inside a native image,
   * which is built from one compilation and needs no such decoding, they are not.
   */
  private static String staleness(final BridgeProvider provider, final MapStep[] steps, final List<String> kinds) {
    if (!kinds.equals(provider.rowKinds())) {
      return "the rows " + provider.rowKinds() + ", but the mapper is built from " + kinds;
    }
    if (NativeImage.IN_IMAGE) return null;
    final var accessors = new ArrayList<String>(steps.length);
    for (final var step : steps) {
      // A write hint names a class rather than accessors; its kind is compared above.
      if (!(step instanceof Mapping<?, ?> row)) continue;
      // A source path is named by the properties it steps through, so a bridge generated from
      // another path into the same target property is passed over.
      final var read =
        row instanceof FromTelescopeTo<?, ?, ?> path ? path.sourceTelescope().pathName() : row.sourceField();
      accessors.add(nullToEmpty(read) + ">" + nullToEmpty(row.targetField()));
    }
    if (accessors.equals(provider.rowAccessors())) return null;
    return (
      "rows naming the accessors " + provider.rowAccessors() + ", but the mapper is built from rows naming " + accessors
    );
  }

  private static String nullToEmpty(final String value) {
    return value == null ? "" : value;
  }

  /**
   * The pairs, as {@code source -> target} binary names, of the {@code @Bridge} fields the
   * telescope processor recorded as read by nobody, because {@code type} was compiled without the
   * javac tree API; empty when it recorded none. Read once per class.
   */
  private static final ClassValue<Set<String>> UNREAD_ROWS = new ClassValue<>() {
    @Override
    protected Set<String> computeValue(final Class<?> type) {
      final var loader = type.getClassLoader() != null ? type.getClassLoader() : DeepMap.class.getClassLoader();
      final var resource = loader.getResource(BridgeProvider.UNREAD_ROWS_PREFIX + type.getName());
      if (resource == null) return Set.of();
      try (final var in = resource.openStream()) {
        return Set.copyOf(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip).toList());
      } catch (final IOException e) {
        return Set.of();
      }
    }
  };

  private static final Logger LOGGER = System.getLogger(Telescope.class.getName());

  /** The mapper over the generated bridge {@code declaration} found. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  static <A, B> Mapper<A, B> declaredMapper(
    final Declaration declaration,
    final Class<A> source,
    final Class<B> target
  ) {
    final var bridge = declaration.provider().bridge();
    final var fn = (BridgeFn<A, B>) Telescope.bridgeFnOf(bridge);
    final var built = Mapper.ofGenerated(
      fn,
      source,
      target,
      Telescope.bridgePatch((Telescope) bridge),
      declaration.provider().targetProperties(),
      List.of(declaration.node())
    );
    return (Mapper<A, B>) BridgeRegistry.specialise(declaration.provider(), built);
  }

  /** As {@link #declaredMapper}, for a mapper converting forward only. */
  @SuppressWarnings("unchecked")
  static <A, B> ForwardMapper<A, B> declaredForwardMapper(
    final Declaration declaration,
    final Class<A> source,
    final Class<B> target
  ) {
    final var fn = (BridgeFn<A, B>) Telescope.bridgeFnOf(declaration.provider().bridge());
    final var built = ForwardMapper.ofGenerated(fn, source, target, List.of(declaration.node()));
    return (ForwardMapper<A, B>) BridgeRegistry.specialise(declaration.provider(), built);
  }

  static <A, B> Mapper<A, B> resolveMapper(final Class<A> source, final Class<B> target, final MapStep[] steps) {
    return resolveMapper(source, target, steps, null);
  }

  /**
   * As {@link #resolveMapper(Class, Class, MapStep[])}, with {@code note}, when there is one, last
   * in the trail {@code explain()} reports.
   */
  static <A, B> Mapper<A, B> resolveMapper(
    final Class<A> source,
    final Class<B> target,
    final MapStep[] steps,
    final OpticNode note
  ) {
    // Bidirectional: strict bijection — unmatched fields on EITHER side throw at construction.
    // Round-trip safety depends on every field having a same-name counterpart or an explicit row.
    final var r = resolution(source, target, steps, false);
    final var trail = noted(r.trail, note);
    // Pass the leaf Iso straight through (Mapper.ofIso) rather than re-wrapping it in a second
    // Iso.of via the Function-typed create — one fewer virtual Iso.to hop per forward/backward. The
    // trail rides along so explain() can surface the field decisions this resolution just made.
    if (r.cases != null) return Mapper.ofCases(r.iso, source, target, r.cases, trail);
    return Mapper.ofIso(r.iso, source, target, r.patchTable, trail, r.sourceRefl);
  }

  private static List<OpticNode> noted(final List<OpticNode> trail, final OpticNode note) {
    if (note == null) return trail;
    final var noted = new ArrayList<>(trail);
    noted.add(note);
    return noted;
  }

  /**
   * Forward-only resolution: lenient by default. There's no {@code backward()} call to satisfy, so
   * the bijection invariant doesn't apply — unmatched target fields silently receive JLS defaults,
   * unmatched source fields are silently ignored. Matches MapStruct's default behaviour for every
   * mapper and removes the "13 drops + 2 constants for a 3-field rename" friction adopters hit on a
   * small-DTO ↔ large-entity migration shape.
   */
  static <A, B> Iso<A, B> resolveForward(final Class<A> source, final Class<B> target, final MapStep[] steps) {
    return resolution(source, target, steps, true).iso;
  }

  /**
   * Forward-only resolution that also carries the introspection trail, so {@code
   * Telescope.mapperForward}'s row path produces a {@link ForwardMapper} whose {@code explain()}
   * surfaces its field decisions — including the lenient {@code MISSING_SOURCE} skips and {@code
   * UnusedSource} rows a strict {@code Mapper} would reject.
   */
  static <A, B> ForwardMapper<A, B> resolveForwardMapper(
    final Class<A> source,
    final Class<B> target,
    final MapStep[] steps
  ) {
    return resolveForwardMapper(source, target, steps, null);
  }

  /**
   * As {@link #resolveForwardMapper(Class, Class, MapStep[])}, with {@code note} last in the trail.
   */
  static <A, B> ForwardMapper<A, B> resolveForwardMapper(
    final Class<A> source,
    final Class<B> target,
    final MapStep[] steps,
    final OpticNode note
  ) {
    final var r = resolution(source, target, steps, true);
    return ForwardMapper.create(r.iso::to, source, target, noted(r.trail, note));
  }

  // ---------- Resolution (shared by both public entries) ----------

  @SuppressWarnings("unchecked")
  private static <A, B> Resolution<A, B> resolution(
    final Class<A> source,
    final Class<B> target,
    final MapStep[] steps,
    final boolean lenient
  ) {
    final var overrides = new ArrayList<Mapping<?, ?>>();
    final var hints = new ArrayList<WriteHint<?>>();
    final var nullHints = new ArrayList<NullHint>();
    final var caseRows = new ArrayList<Cases>();
    for (final var step : steps) {
      switch (step) {
        case Mapping<?, ?> m -> overrides.add(m);
        case WriteHint<?> h -> hints.add(h);
        case NullHint nh -> nullHints.add(nh);
        case Cases c -> caseRows.add(c);
      }
    }
    final var nullStrategy = extractNullStrategy(nullHints);
    final var hintMap = buildHintMap(hints);
    final var defaultStrategy = extractDefaultStrategy(hints);
    final var defaultWriterFactory = defaultWriterFactoryFor(defaultStrategy);
    // One bean-side Reflective per resolution call: a singleton Reflective.BEANS when no hints
    // exist, otherwise one hint-aware Reflective threaded through every recursion call so the
    // anonymous instance isn't re-allocated per type pair.
    final var beanRefl =
      hintMap.isEmpty() && defaultWriterFactory == null
        ? Reflective.BEANS
        : Reflective.beansWithHints(hintMap, defaultWriterFactory);
    final var overrideTable = groupOverridesByPair(overrides.toArray(Mapping<?, ?>[]::new), source, target);
    final var cache = new HashMap<TypePair, Iso<?, ?>>();
    final var topSteps = new LinkedHashMap<String, FieldStep>();
    final var cyclicPairs = new HashSet<TypePair>();
    final var inProgress = new ArrayDeque<TypePair>();
    final var trail = new ArrayList<OpticNode>();
    final var ownBridges = new OwnBridges(
      overrideTable,
      caseRows,
      hintMap.keySet(),
      nullStrategy == NullHint.NullStrategy.DEFAULT
        ? "the mapper's nullSourceValues(DEFAULT) reaches every pair, and a bridge has no counterpart for it"
        : defaultStrategy != null
          ? "the mapper's writeBeans(...) default reaches every pair, and a bridge has no counterpart for it"
          : null,
      cyclicPairs
    );
    final var sealedCases = new SealedCases(caseRows);
    // The pairs the mapper visits are walked before anything is built. A row keyed to the mapper's
    // own pair is always visited, so only a row keyed below it needs the walk. A row the walk never
    // reaches is refused once the build has refused nothing of its own, so a property the build
    // cannot convert, or a field with no counterpart, is the refusal named.
    final var walked = keyedBelow(overrideTable, source, target)
      ? PAIR_WALK.walk(source, target, keyedRows(overrideTable), lenient, new Walking(ownBridges, sealedCases, lenient))
      : null;
    // A mapper between two sealed interfaces dispatches on the case, and each case's own mapper is
    // what patch and into reach; any other pair is walked by property.
    final var rootCases =
      PAIRING.decidePair(source, target, "", lenient) instanceof PairDecision.DispatchCases
        ? new ArrayList<Mapper.CaseEntry>()
        : null;
    if (rootCases != null) populateSealed(
      source,
      target,
      overrideTable,
      beanRefl,
      cache,
      nullStrategy,
      cyclicPairs,
      inProgress,
      ownBridges,
      sealedCases,
      lenient,
      lenient,
      rootCases,
      trail
    );
    else populateIso(
      source,
      target,
      overrideTable,
      beanRefl,
      cache,
      topSteps,
      nullStrategy,
      cyclicPairs,
      inProgress,
      ownBridges,
      sealedCases,
      lenient,
      lenient,
      trail
    );
    validateAllHintsConsumed(hintMap, cache);
    if (walked != null) {
      // Every pair that leaves the runtime walk open is one the build refuses, so a build that
      // finished was walked to the end.
      assert !walked.open() && visitsAgree(walked, cache.keySet()) : "the build visited other pairs than the walk";
      if (walked.refusal() != null) throw new IllegalArgumentException(walked.refusal());
    }
    sealedCases.refuseUntaken(source, target);
    final var iso = (Iso<A, B>) Objects.requireNonNull(cache.get(new TypePair(source, target)));
    final var patchTable = patchTableOf(topSteps, source);
    final Iso<A, B> guarded = cyclicPairs.isEmpty()
      ? iso
      : Iso.of(
          value -> inInvocation(FORWARD_SEEN, ownBridges, source, target, value, iso::to),
          value -> inInvocation(BACKWARD_SEEN, ownBridges, target, source, value, iso::from)
        );
    return new Resolution<>(guarded, patchTable, rootCases, List.copyOf(trail), pickReflective(source, beanRefl));
  }

  /**
   * The patch table for a pair whose fields {@code steps} resolved, keyed by target field, in the
   * order {@code source} declares the fields they write. Patch converts the entries in table order,
   * so a row's field and a walked one convert in the source's own order, as forward and backward
   * convert them, rather than in the order the steps were resolved. A step writing no source field,
   * a constant or a computed value, comes after the rest, in the order it was resolved, and a step
   * writing its value back through a source path comes last, in the order its rows came.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Mapper.PatchEntry> patchTableOf(
    final Map<String, FieldStep> steps,
    final Class<?> source
  ) {
    final var order = List.of((source.isRecord() ? Reflective.RECORDS : Reflective.BEANS).names(source));
    final var entries = new ArrayList<>(steps.entrySet());
    entries.sort(
      Comparator.comparingInt(entry -> {
        final var step = entry.getValue();
        if (step.overlay != null) return Integer.MAX_VALUE;
        final var index = step.sourceName == null ? -1 : order.indexOf(step.sourceName);
        return index < 0 ? Integer.MAX_VALUE - 1 : index;
      })
    );
    final var patchTable = new LinkedHashMap<String, Mapper.PatchEntry>();
    for (final var entry : entries) {
      final var step = entry.getValue();
      patchTable.put(
        entry.getKey(),
        new Mapper.PatchEntry(step.sourceName, v -> ((Iso<Object, Object>) step.iso).from(v), step.overlay)
      );
    }
    return patchTable;
  }

  // ---------- Hint validation + writer eager construction ----------

  /**
   * Build the {@code targetClass -> BeanWriter} map from the hint list. Validates eagerly:
   * duplicate-target hints, record-targeted hints, and incompatible strategies (e.g. {@code
   * BUILDER} on a class with no static {@code builder()}) all throw at this point — so a
   * misconfigured hint surfaces at {@code Telescope.map(...)} call time rather than at the first
   * {@code iso.to()} invocation.
   */
  private static Map<Class<?>, Beans.BeanWriter<?>> buildHintMap(final List<WriteHint<?>> hints) {
    final var map = new HashMap<Class<?>, Beans.BeanWriter<?>>();
    for (final var hint : hints) {
      if (hint instanceof WriteHint.DefaultWriteHint) continue; // handled by extractDefaultStrategy
      final var cls = hint.targetClass();
      if (cls.isRecord()) throw new IllegalArgumentException(PairingMessages.writeBeanTargetsRecord(cls.getName()));
      if (map.containsKey(cls)) throw new IllegalArgumentException(
        PairingMessages.duplicateWriteBeanHint(cls.getName())
      );
      map.put(cls, writerFor(hint));
    }
    return map;
  }

  /**
   * Pull out the single optional {@link NullHint#nullSourceValues(NullHint.NullStrategy)
   * nullSourceValues(…)} strategy. Returns {@link NullHint.NullStrategy#PROPAGATE} when no hint is
   * supplied (the v0.x default); throws on duplicates so the misconfiguration surfaces at
   * mapper-build time rather than at first apply.
   */
  private static NullHint.NullStrategy extractNullStrategy(final List<NullHint> hints) {
    NullHint.NullStrategy strategy = null;
    for (final var hint : hints) {
      if (strategy != null) throw new IllegalArgumentException(
        "Duplicate nullSourceValues(...) hint — at most one null-source-value strategy per " +
          "Telescope.map(...) call."
      );
      strategy = hint.strategy();
    }
    return strategy == null ? NullHint.NullStrategy.PROPAGATE : strategy;
  }

  /**
   * Pull out the single optional {@link WriteHint#writeBeans(WriteHint.WriteStrategy)
   * writeBeans(…)} default strategy. Returns {@code null} when no default is supplied; throws on
   * duplicates.
   */
  private static WriteHint.WriteStrategy extractDefaultStrategy(final List<WriteHint<?>> hints) {
    WriteHint.WriteStrategy defaultStrategy = null;
    for (final var hint : hints) {
      if (!(hint instanceof WriteHint.DefaultWriteHint(final WriteHint.WriteStrategy strat))) continue;
      if (defaultStrategy != null) throw new IllegalArgumentException(PairingMessages.duplicateWriteBeansDefault());
      defaultStrategy = strat;
    }
    return defaultStrategy;
  }

  private static Beans.BeanWriter<?> writerFor(final WriteHint<?> hint) {
    // Each *Writer constructor throws IllegalStateException with a writeBean(class, STRATEGY)-
    // shaped message when its prerequisite is missing, so no rewrap is needed — the underlying
    // exception already names the actual API the user called.
    final Class<?> cls = hint.targetClass();
    return writerFor(cls, hint.strategy());
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Beans.BeanWriter<?> writerFor(final Class<?> cls, final WriteHint.WriteStrategy strategy) {
    final var raw = (Class) cls;
    return switch (strategy) {
      case BUILDER -> Beans.builderWriter(raw);
      case SETTERS -> Beans.settersWriter(raw);
      case CONSTRUCTOR -> Beans.constructorWriter(raw, Beans.propertyNames(raw).length);
    };
  }

  /**
   * Build a per-class writer factory that materializes the {@code writeBeans(strategy)} default
   * lazily on first encounter with each unhinted target. Results are cached so the lookup is O(1)
   * after the first resolve. Returns {@code null} when no default was supplied.
   */
  private static Function<Class<?>, Beans.BeanWriter<?>> defaultWriterFactoryFor(
    final WriteHint.WriteStrategy defaultStrategy
  ) {
    if (defaultStrategy == null) return null;
    final var cache = new ConcurrentHashMap<Class<?>, Beans.BeanWriter<?>>();
    return cls -> cache.computeIfAbsent(cls, c -> writerFor(c, defaultStrategy));
  }

  /**
   * Reject any {@code writeBean(...)} hint whose target class was never encountered <em>on either
   * side</em> of a resolved type pair. Source-side classes are constructed during {@code
   * Mapper.backward} / {@code Iso.from}, so a hint on a bean source root (e.g. {@code map(Bean,
   * Record, writeBean(Bean, ...))}) is genuinely used — it governs the backward direction. Silently
   * swallowed hints (typo in the class literal, refactored class) still surface here at resolve
   * time rather than slipping into production.
   */
  private static void validateAllHintsConsumed(
    final Map<Class<?>, Beans.BeanWriter<?>> hintMap,
    final Map<TypePair, Iso<?, ?>> cache
  ) {
    if (hintMap.isEmpty()) return;
    final var seen = new HashSet<Class<?>>();
    for (final var pair : cache.keySet()) {
      seen.add(pair.source);
      seen.add(pair.target);
    }
    final var unused = new ArrayList<String>();
    for (final var hintCls : hintMap.keySet()) if (!seen.contains(hintCls)) unused.add(hintCls.getName());
    if (!unused.isEmpty()) throw new IllegalArgumentException(
      "Unused writeBean hints — classes never encountered during deep-mapping recursion: " +
        String.join(", ", unused) +
        ". Remove the row, or verify the class is actually reached by the source/target" +
        " structure."
    );
  }

  /** Whether a row is keyed to a pair other than the mapper's own. */
  private static boolean keyedBelow(
    final Map<TypePair, List<Mapping<?, ?>>> overrideTable,
    final Class<?> topSource,
    final Class<?> topTarget
  ) {
    for (final var pair : overrideTable.keySet()) {
      if (pair.source != topSource || pair.target != topTarget) return true;
    }
    return false;
  }

  /** The rows of {@code overrideTable}, each with the pair it is keyed to, in the order given. */
  private static List<PairWalk.KeyedRow<Type>> keyedRows(final Map<TypePair, List<Mapping<?, ?>>> overrideTable) {
    final var rows = new ArrayList<PairWalk.KeyedRow<Type>>();
    for (final var entry : overrideTable.entrySet()) {
      final var pair = entry.getKey();
      for (final var row : entry.getValue()) rows.add(keyedRow(pair, row));
    }
    return rows;
  }

  /** {@code row} and the pair it is keyed to, as the shared walk reads it. */
  private static PairWalk.KeyedRow<Type> keyedRow(final TypePair pair, final Mapping<?, ?> row) {
    final Mapping<?, ?> applied = row instanceof Conditional<?, ?> c ? c.inner() : row;
    final String sourcePathStart = switch (applied) {
      case FromTelescopeTo<?, ?, ?> t -> t.sourceTelescope().firstHopName();
      case TelescopeToTelescope<?, ?, ?> t -> t.sourceTelescope().firstHopName();
      default -> null;
    };
    final String targetPathStart = switch (applied) {
      case TelescopeTo<?, ?, ?> t -> t.targetTelescope().firstHopName();
      case TelescopeToTelescope<?, ?, ?> t -> t.targetTelescope().firstHopName();
      case Constant<?, ?, ?> c -> c.targetTelescope().firstHopName();
      case Compute<?, ?, ?> c -> c.targetTelescope().firstHopName();
      default -> null;
    };
    return new PairWalk.KeyedRow<>(
      pair.source,
      pair.target,
      kindOf(row),
      row.sourceField(),
      row.targetField(),
      sourcePathStart,
      targetPathStart
    );
  }

  /**
   * The rows {@code key}'s pair applies, as the shared walk applies them: those keyed to it, then
   * each row keyed to two sealed interfaces its classes implement that the shared rules let apply
   * ({@link PairWalk#inherited}), in the order given, with a side given as a path started at the
   * pair's own class. A row the pair refuses is refused by name.
   */
  private static List<Mapping<?, ?>> rowsAt(final Map<TypePair, List<Mapping<?, ?>>> overrides, final TypePair key) {
    final var own = overrides.getOrDefault(key, List.of());
    if (!inheritsRows(overrides, key)) return own;
    final var candidates = new ArrayList<PairWalk.KeyedRow<Type>>();
    final var rowOf = new IdentityHashMap<PairWalk.KeyedRow<Type>, Mapping<?, ?>>();
    for (final var entry : overrides.entrySet()) {
      final var keyed = entry.getKey();
      if (keyed.equals(key)) continue;
      for (final var row : entry.getValue()) {
        final var candidate = keyedRow(keyed, row);
        candidates.add(candidate);
        rowOf.put(candidate, row);
      }
    }
    final var ownKeyed = new ArrayList<PairWalk.KeyedRow<Type>>(own.size());
    for (final var row : own) ownKeyed.add(keyedRow(key, row));
    final var inherited = PAIR_WALK.inherited(candidates, ownKeyed, key.source, key.target);
    if (!inherited.refused().isEmpty()) throw new IllegalStateException(inherited.refused().getFirst().message());
    if (inherited.applied().isEmpty()) return own;
    final var all = new ArrayList<Mapping<?, ?>>(own);
    for (final var row : inherited.applied()) all.add(rootedRow(rowOf.get(row), key.source, key.target));
    return all;
  }

  /**
   * Whether a row writes the property {@code name} of {@code target} through its target path, past
   * the property of the pair it is keyed to, or may: true too when a row's target path records no
   * hops, so what it writes is not known.
   */
  private static boolean writtenThroughPath(
    final Map<TypePair, List<Mapping<?, ?>>> overrides,
    final Class<?> target,
    final String name
  ) {
    for (final var rows : overrides.values()) {
      for (final var raw : rows) {
        final Mapping<?, ?> row = raw instanceof Conditional<?, ?> c ? c.inner() : raw;
        final Telescope<?, ?> path = switch (row) {
          case TelescopeTo<?, ?, ?> t -> t.targetTelescope();
          case TelescopeToTelescope<?, ?, ?> t -> t.targetTelescope();
          case Constant<?, ?, ?> t -> t.targetTelescope();
          case Compute<?, ?, ?> t -> t.targetTelescope();
          default -> null;
        };
        if (path == null) continue;
        if (path.hops == null) return true;
        for (var i = path.rootStepIndex() + 1; i < path.hops.size(); i++) {
          final var hop = path.hops.get(i);
          if (hop.kind() != Fusion.Hop.Kind.FIELD || hop.owner() == null) continue;
          if (name.equals(hop.component()) && hop.owner().isAssignableFrom(target)) return true;
        }
      }
    }
    return false;
  }

  /**
   * A {@code drop} given no target class whose accessor is applied to {@code source} and reads its
   * field {@code name}, or null. Such a drop is keyed to the mapper's own target class, so it drops
   * nothing at a pair of {@code source} and another target.
   */
  private static Drop<?, ?, ?> topLevelDrop(
    final Map<TypePair, List<Mapping<?, ?>>> overrides,
    final Class<?> source,
    final Reflective srcRefl,
    final String name
  ) {
    for (final var rows : overrides.values()) {
      for (final var row : rows) {
        if (
          row instanceof Drop<?, ?, ?> drop &&
          drop.targetClass() == null &&
          drop.sourceClass() == source &&
          name.equals(srcRefl.normalize(drop.sourceField()))
        ) {
          return drop;
        }
      }
    }
    return null;
  }

  /** Whether a row keyed to two sealed interfaces the pair's classes implement applies to it. */
  private static boolean inheritsRows(final Map<TypePair, List<Mapping<?, ?>>> overrides, final TypePair pair) {
    for (final var keyed : overrides.keySet()) {
      if (PAIR_WALK.inheritsRows(pair.source, pair.target, keyed.source, keyed.target)) return true;
    }
    return false;
  }

  /**
   * What {@code row} does at the pair it is keyed to, as {@link #populateIso} applies it. A {@code
   * when} row wraps only a row through a path, which its factory holds it to.
   */
  private static PairWalk.RowKind kindOf(final Mapping<?, ?> row) {
    return switch (row) {
      case SameTypedTo<?, ?, ?> __ -> PairWalk.RowKind.PASS_THROUGH;
      case TypedTransformTo<?, ?, ?, ?> __ -> PairWalk.RowKind.CONVERTING;
      case ForwardOnlyTransformTo<?, ?, ?, ?> __ -> PairWalk.RowKind.CONVERTING;
      case Via<?, ?> __ -> PairWalk.RowKind.CONVERTING;
      case ViaClass<?, ?> __ -> PairWalk.RowKind.CONVERTING;
      case ViaMethods<?, ?> __ -> PairWalk.RowKind.CONVERTING;
      case Drop<?, ?, ?> __ -> PairWalk.RowKind.DROP;
      case TelescopeTo<?, ?, ?> __ -> PairWalk.RowKind.PATH;
      case FromTelescopeTo<?, ?, ?> __ -> PairWalk.RowKind.PATH;
      case TelescopeToTelescope<?, ?, ?> __ -> PairWalk.RowKind.PATH;
      case Constant<?, ?, ?> __ -> PairWalk.RowKind.PATH;
      case Compute<?, ?, ?> __ -> PairWalk.RowKind.PATH;
      case Conditional<?, ?> __ -> PairWalk.RowKind.PATH;
    };
  }

  /** Whether the pairs {@code walked} visited are the pairs the build converted by walking them. */
  private static boolean visitsAgree(final PairWalk.Walk<Type> walked, final Set<TypePair> built) {
    final var visited = new HashSet<TypePair>();
    for (final var pair : walked.visited())
      visited.add(new TypePair((Class<?>) pair.source(), (Class<?>) pair.target()));
    return visited.equals(built);
  }

  /**
   * The walk's questions, answered as the build answers them: a nested pair converts through its
   * own bridge where {@link OwnBridges} lets it, and the cases of two sealed interfaces pair as
   * {@link SealedCases} pairs them.
   */
  private record Walking(
    OwnBridges ownBridges,
    SealedCases sealedCases,
    boolean forwardOnly
  ) implements PairWalk.Decisions<Type> {
    @Override
    public boolean ownBridge(final Type source, final Type target, final List<PairWalk.Pair<Type>> around) {
      final var pair = new TypePair((Class<?>) source, (Class<?>) target);
      if (!ownBridges.mayStandIn(pair)) return false;
      final var ancestors = new ArrayList<TypePair>(around.size());
      for (final var each : around) ancestors.add(new TypePair((Class<?>) each.source(), (Class<?>) each.target()));
      return ownBridges.standsIn(pair, ancestors);
    }

    @Override
    public List<PairWalk.Pair<Type>> casePairs(final Type sourceRoot, final Type targetRoot) {
      final List<TypePair> pairs;
      try {
        pairs = sealedCases.pairsOf(new TypePair((Class<?>) sourceRoot, (Class<?>) targetRoot), forwardOnly);
      } catch (final IllegalStateException | IllegalArgumentException refused) {
        // The build pairs the same cases where it meets the pair and raises this refusal there,
        // after any it raises on the way.
        return null;
      }
      final var cases = new ArrayList<PairWalk.Pair<Type>>();
      for (final var pair : pairs) {
        cases.add(new PairWalk.Pair<>(pair.source, pair.target));
      }
      return cases;
    }
  }

  // ---------- Override grouping ----------

  private static Map<TypePair, List<Mapping<?, ?>>> groupOverridesByPair(
    final Mapping<?, ?>[] overrides,
    final Class<?> topSource,
    final Class<?> topTarget
  ) {
    // In the order each pair's first row was given, which is the order a refusal names them in.
    final var grouped = new LinkedHashMap<TypePair, List<Mapping<?, ?>>>();
    for (final var row : overrides) {
      // A row is keyed to the classes its accessors are applied to. A side that names no class
      // keys to the mapper's own class on that side: drop(srcAcc) has no target, and a side given
      // as a telescope path is read or written on the mapper's own source or target, so the path
      // is started there, checked against it, or refused by rootedRow. A to(path, tgtAcc) row
      // whose target accessor is applied to a superclass or interface of the mapper's target keys
      // to the mapper's target too: that pair is never visited, and the row writes the property by
      // name on the target's own class. A when(...) row is keyed exactly as the row it wraps, so
      // its predicate receives the source of the pair that row is applied at.
      final Mapping<?, ?> keyed = row instanceof Conditional<?, ?> c ? c.inner() : row;
      final Class<?> effectiveSource = keyed.sourceClass() == null ? topSource : keyed.sourceClass();
      final Class<?> effectiveTarget =
        keyed.targetClass() == null ||
        (keyed instanceof FromTelescopeTo<?, ?, ?> && keyed.targetClass().isAssignableFrom(topTarget))
          ? topTarget
          : keyed.targetClass();
      final var key = new TypePair(effectiveSource, effectiveTarget);
      grouped.computeIfAbsent(key, __ -> new ArrayList<>()).add(rootedRow(row, topSource, topTarget));
    }
    return grouped;
  }

  /**
   * A row that reads the top-level source or writes the top-level target through a path, bare or
   * under {@code when}, with each path started at the mapper's own class on its side, checked
   * against it, or refused: the source path of {@code to} or {@code zip} through {@link
   * #sourceRooted}, and the target path of {@code constant}, {@code compute}, {@code to} and {@code
   * zip} through {@link #targetRooted}. A row with both is checked on its source path first. A
   * {@code when} row whose source path is checked on each call runs that check before its
   * predicate, so a source the path cannot read fails as it does on the bare row rather than inside
   * a predicate typed by the path's root. Every other row is returned unchanged.
   */
  @SuppressWarnings("unchecked")
  private static Mapping<?, ?> rootedRow(final Mapping<?, ?> row, final Class<?> topSource, final Class<?> topTarget) {
    return switch (row) {
      case Constant<?, ?, ?> c -> new Constant<>(
        targetRooted(c.targetTelescope(), "constant", topSource, topTarget, true, c.value()),
        (Object) c.value()
      );
      case Compute<?, ?, ?> c -> new Compute<>(
        targetRooted(c.targetTelescope(), "compute", topSource, topTarget, false, null),
        c.supplier()
      );
      case TelescopeTo<?, ?, ?> t -> new TelescopeTo<>(
        (Telescope.Accessor<Object, Object>) t.srcAccessor(),
        targetRooted(t.targetTelescope(), "to", topSource, topTarget, false, null)
      );
      case FromTelescopeTo<?, ?, ?> t -> new FromTelescopeTo<>(
        sourceRooted(t.sourceTelescope(), "to", topSource, topTarget).path(),
        (Telescope.Accessor<Object, Object>) t.tgtAccessor()
      );
      case TelescopeToTelescope<?, ?, ?> t -> {
        final var name = t.kind() == TelescopeToTelescope.Kind.ZIP ? "zip" : "to";
        final var source = sourceRooted(t.sourceTelescope(), name, topSource, topTarget).path();
        yield new TelescopeToTelescope<>(
          source,
          targetRooted(t.targetTelescope(), name, topSource, topTarget, false, null),
          t.kind()
        );
      }
      case Conditional<?, ?> c -> {
        final var inner = rootedRow(c.inner(), topSource, topTarget);
        final var given = switch (c.inner()) {
          case FromTelescopeTo<?, ?, ?> t -> sourceRooted(t.sourceTelescope(), "to", topSource, topTarget).given();
          case TelescopeToTelescope<?, ?, ?> t -> sourceRooted(
            t.sourceTelescope(),
            t.kind() == TelescopeToTelescope.Kind.ZIP ? "zip" : "to",
            topSource,
            topTarget
          ).given();
          default -> null;
        };
        final var predicate = (Predicate<Object>) c.predicate();
        yield new Conditional<>(
          given == null ? predicate : new TelescopeFixups.SourceChecked(given, predicate),
          (Mapping<Object, Object>) inner
        );
      }
      default -> row;
    };
  }

  /**
   * A source path as a row reads it, and the check it runs on each source it is given before
   * reading it, or null when it runs none.
   */
  private record SourceRoot(Telescope<Object, Object> path, Function<Object, Object> given) {}

  /**
   * Whether one object can be an instance of both {@code a} and {@code b}: one extends the other;
   * one is sealed and one of its permitted cases can be such an instance, since every instance of a
   * sealed type is an instance of one of its cases; or one is an interface and the other is a type
   * a subclass can still extend to implement it.
   */
  static boolean mayBeInstance(final Class<?> a, final Class<?> b) {
    if (a.isAssignableFrom(b) || b.isAssignableFrom(a)) return true;
    if (a.isSealed()) return Arrays.stream(a.getPermittedSubclasses()).anyMatch(c -> mayBeInstance(c, b));
    if (b.isSealed()) return Arrays.stream(b.getPermittedSubclasses()).anyMatch(c -> mayBeInstance(a, c));
    if (a.isInterface()) return extendable(b);
    return b.isInterface() && extendable(a);
  }

  /**
   * Whether a type that is not sealed can gain a subclass that implements another interface. An
   * interface or a non-final class can. A final class cannot, and a record is final, as is the
   * anonymous class of an enum constant's body. Neither can an enum: one whose constants declare
   * bodies is not marked final, but its only subclasses are those bodies, and a body cannot
   * implement an interface its enum does not.
   */
  private static boolean extendable(final Class<?> type) {
    return !Modifier.isFinal(type.getModifiers()) && !type.isEnum();
  }

  /**
   * {@code path}, which a row reads the top-level source through, started at that source, checked
   * against it, or refused when it cannot read there.
   *
   * <p>The hops before the path's first step off its root value keep the focus on that value. A
   * filter or an observer is kept whatever its class. A narrowing to a class the source is or
   * extends always matches and is kept. A narrowing to another class a source may be an instance
   * of, such as a subclass of the source, matches only such a source, which is what a narrowing is
   * for, so the path is kept as it is. A narrowing to a class no source can be an instance of never
   * matches and is refused.
   *
   * <p>A first step that reads a component through an accessor is read again on the source when its
   * class is the source or a type the source extends, since a write through a hop rebuilds the
   * class that hop reads. A component of an interface the source does not implement, but that a
   * source may, is kept and each source it is given is checked to implement it. A component of any
   * other class is refused: a component of a subclass is read from a source of that subclass by
   * narrowing to it first. A first step by {@code fieldByName} reads the property on whatever class
   * it is given and is kept as it is.
   *
   * <p>Any other path cannot be started again: one that starts at a lens, reaches its first
   * property through an iso or a bridge, or reads the root value itself. Such a path is kept when
   * its root class is the source, and refused when no source can be an instance of its root class.
   * A path rooted at another class a source may be an instance of reads only a source of that
   * class, so each source it is given is checked to be one before it is read or written. A path
   * rooted at a supertype reads any source, and one whose root class is unknown, as for a lambda
   * lens, an iso or a bridge, may, so each is kept and each write's result is checked to be a
   * source instance or null.
   */
  @SuppressWarnings("unchecked")
  private static SourceRoot sourceRooted(
    final Telescope<?, ?> path,
    final String row,
    final Class<?> topSource,
    final Class<?> topTarget
  ) {
    final var hops = path.hops;
    final var kept = (Telescope<Object, Object>) path;
    final Class<?> focus = path.rootClass;
    final var step = path.rootStepIndex();
    final var leading = hops == null ? 0 : step < 0 ? hops.size() : step;
    final var source = topSource.getSimpleName();
    final var target = topTarget.getSimpleName();
    for (var i = 0; i < leading; i++) {
      final var narrowed = hops.get(i).narrowedTo();
      if (narrowed == null || narrowed.isAssignableFrom(topSource)) continue;
      if (!mayBeInstance(topSource, narrowed)) throw new IllegalArgumentException(
        PairingMessages.sourcePathNarrowsOffSource(source, target, row, narrowed.getSimpleName())
      );
      return new SourceRoot(kept, null);
    }
    if (step >= 0) {
      final var first = hops.get(step);
      final var owner = first.owner();
      if (owner != null) {
        if (owner.isAssignableFrom(topSource)) {
          return new SourceRoot(kept.rootedAt((Class<Object>) topSource), null);
        }
        if (owner.isInterface() && mayBeInstance(topSource, owner)) return givenOnly(kept, owner, row, source, target);
        throw new IllegalArgumentException(
          PairingMessages.sourceRowOffSource(source, target, row, owner.getSimpleName(), first.component())
        );
      }
      if (first.kind() == Fusion.Hop.Kind.BY_NAME) return new SourceRoot(kept, null);
    }
    if (focus == topSource) return new SourceRoot(kept, null);
    if (focus != null && !focus.isAssignableFrom(topSource)) {
      if (!mayBeInstance(topSource, focus)) throw new IllegalArgumentException(
        PairingMessages.sourcePathNotOnSource(source, target, row, PairingMessages.className(focus))
      );
      return givenOnly(kept, focus, row, source, target);
    }
    final Function<Object, Object> checked = written -> {
      if (written == null || topSource.isInstance(written)) return written;
      throw new IllegalStateException(
        PairingMessages.sourcePathWroteOffSource(source, target, row, PairingMessages.className(written.getClass()))
      );
    };
    return new SourceRoot(Telescope.<Object, Object>iso(Function.identity(), checked).then(kept), null);
  }

  /**
   * {@code path}, given only a source that is an instance of {@code root}. A row never receives a
   * null source: a null converts to null before any row runs.
   */
  private static SourceRoot givenOnly(
    final Telescope<Object, Object> path,
    final Class<?> root,
    final String row,
    final String source,
    final String target
  ) {
    final Function<Object, Object> given = value -> {
      if (root.isInstance(value)) return value;
      throw new IllegalStateException(
        PairingMessages.sourcePathGivenOffRoot(
          source,
          target,
          row,
          PairingMessages.className(root),
          PairingMessages.className(value.getClass())
        )
      );
    };
    return new SourceRoot(Telescope.<Object, Object>iso(given, Function.identity()).then(path), given);
  }

  /**
   * {@code path}, which a row writes on the top-level target, started at that target, checked
   * against it, or refused when it cannot write there.
   *
   * <p>The hops before the path's first step off its root value keep the focus on that value: a
   * filter or an observer whatever its class, and a narrowing only when the target is the class it
   * narrows to or extends it, so any other narrowing never matches and is refused. A first step
   * that reads a component through an accessor is read again on the target when its class is the
   * target or a type the target extends, and refused otherwise. A first step by {@code fieldByName}
   * reads the property on whatever class it is given and is kept as it is.
   *
   * <p>Any other path cannot be started again: one that starts at a lens, reaches its first
   * property through an iso or a bridge, or writes the root value itself. Such a path is kept when
   * its root class is the target, and refused when its root class is neither a supertype nor a
   * subtype of the target. A path rooted at a supertype or subtype, or at a class that is unknown,
   * as for a lambda lens, an iso or a bridge, may still write the target correctly, since a lens's
   * setter receives the target instance, so it is kept and each write's result is checked to be a
   * target instance or null. A {@code constant} that replaces the root value of a path with a known
   * root is checked once, here: its value must be a target instance or null. A check on the result
   * runs after the path's own write, so a path that builds an abstract class fails inside that
   * write, before the check sees anything.
   */
  @SuppressWarnings("unchecked")
  private static Telescope<Object, Object> targetRooted(
    final Telescope<?, ?> path,
    final String row,
    final Class<?> topSource,
    final Class<?> topTarget,
    final boolean constantRow,
    final Object constantValue
  ) {
    final var hops = path.hops;
    Class<?> focus = path.rootClass;
    final var step = path.rootStepIndex();
    final var leading = hops == null ? 0 : step < 0 ? hops.size() : step;
    for (var i = 0; i < leading; i++) {
      final var narrowed = hops.get(i).narrowedTo();
      if (narrowed == null) continue;
      if (!narrowed.isAssignableFrom(topTarget)) throw new IllegalArgumentException(
        PairingMessages.targetPathNarrowsOffTarget(
          topSource.getSimpleName(),
          topTarget.getSimpleName(),
          row,
          narrowed.getSimpleName()
        )
      );
      focus = narrowed;
    }
    if (step >= 0) {
      final var first = hops.get(step);
      if (first.owner() != null) {
        if (!first.owner().isAssignableFrom(topTarget)) throw new IllegalArgumentException(
          PairingMessages.targetRowOffTarget(
            topSource.getSimpleName(),
            topTarget.getSimpleName(),
            row,
            first.owner().getSimpleName(),
            first.component()
          )
        );
        return ((Telescope<Object, Object>) path).rootedAt((Class<Object>) topTarget);
      }
      if (first.kind() == Fusion.Hop.Kind.BY_NAME) return (Telescope<Object, Object>) path;
    }
    if (focus == topTarget) return (Telescope<Object, Object>) path;
    final var source = topSource.getSimpleName();
    final var target = topTarget.getSimpleName();
    if (focus != null) {
      if (!focus.isAssignableFrom(topTarget) && !topTarget.isAssignableFrom(focus)) throw new IllegalArgumentException(
        PairingMessages.targetPathNotOnTarget(source, target, row, focus.getSimpleName())
      );
      if (hops != null && step < 0 && constantRow) {
        if (constantValue != null && !topTarget.isInstance(constantValue)) throw new IllegalArgumentException(
          PairingMessages.targetValueOffTarget(source, target, row, PairingMessages.className(constantValue.getClass()))
        );
        return (Telescope<Object, Object>) path;
      }
    }
    final Function<Object, Object> checked = written -> {
      if (written == null || topTarget.isInstance(written)) return written;
      throw new IllegalStateException(
        PairingMessages.targetPathWroteOffTarget(source, target, row, PairingMessages.className(written.getClass()))
      );
    };
    return Telescope.<Object, Object>iso(Function.identity(), checked).then((Telescope<Object, Object>) path);
  }

  // ---------- Recursive resolver (writes into the cache) ----------

  /**
   * The introspection node for one resolved field: {@link OpticNode.Mapped} when the source and
   * target types are identical (a same-name or renamed pass-through), {@link OpticNode.Transformed}
   * otherwise (a type change — boxing, container lift, cross-type row).
   */
  private static OpticNode fieldNode(
    final String targetField,
    final String sourceField,
    final Type srcType,
    final Type tgtType
  ) {
    if (srcType.equals(tgtType)) return new OpticNode.Mapped(sourceField, targetField);
    return new OpticNode.Transformed(sourceField, targetField, simpleTypeName(srcType), simpleTypeName(tgtType));
  }

  /**
   * One {@link OpticNode.Dispatched} per case pairing when {@code (srcType, tgtType)} is a pair of
   * sealed interfaces the walk dispatched on, under {@code path}.
   */
  private static void addDispatched(
    final List<OpticNode> out,
    final String path,
    final Type srcType,
    final Type tgtType,
    final SealedCases sealedCases
  ) {
    final var srcCls = rawClassOf(srcType);
    final var tgtCls = rawClassOf(tgtType);
    if (srcCls == null || tgtCls == null) return;
    final var pairs = sealedCases.decided(new TypePair(srcCls, tgtCls));
    if (pairs == null) return;
    for (final var pair : pairs) {
      out.add(new OpticNode.Dispatched(path, pair.source, pair.target));
    }
  }

  /**
   * The name a report gives a type. Shared with the fromMap trail so the two cannot drift apart.
   */
  static String simpleTypeName(final Type t) {
    return t instanceof Class<?> c ? c.getSimpleName() : t.getTypeName();
  }

  /**
   * True when {@code (srcType, tgtType)} recurses into a nested record/bean pair AND carries no
   * explicit override rows — the pure-auto nesting the introspection trail can safely descend into
   * with dotted paths. A nested pair WITH rows is left as a single node (conservative): its rows
   * are resolved by {@code populateIso}, not re-walked here. A pair converted by its own bridge is
   * a single node too, because the bridge decides its fields and the walk would describe others.
   */
  private static boolean isNestedAutoPair(
    final Type srcType,
    final Type tgtType,
    final Map<TypePair, List<Mapping<?, ?>>> overrides,
    final OwnBridges ownBridges
  ) {
    if (!(srcType instanceof Class<?> srcCls) || !(tgtType instanceof Class<?> tgtCls)) return false;
    if (!(PAIRING.decidePair(srcType, tgtType, "") instanceof PairDecision.RecursePair)) return false;
    final var pair = new TypePair(srcCls, tgtCls);
    return (
      overrides.getOrDefault(pair, List.of()).isEmpty() && !inheritsRows(overrides, pair) && !ownBridges.converts(pair)
    );
  }

  /**
   * Emit dotted-path introspection nodes for a pure-auto nested pair, recursing to any depth. Uses
   * the same {@link PairingRules#matchFields} name-matching and {@link PairingRules#decidePair}
   * classification the resolver uses, so the nested rows are faithful; only fires for pairs with no
   * override rows (see {@link #isNestedAutoPair}). {@code prefix} is the dotted path accumulated so
   * far (e.g. {@code "customer.address."}).
   */
  private static void collectNested(
    final Class<?> srcCls,
    final Class<?> tgtCls,
    final String prefix,
    final Map<TypePair, List<Mapping<?, ?>>> overrides,
    final OwnBridges ownBridges,
    final Reflective beanRefl,
    final List<OpticNode> out,
    final Set<TypePair> seen
  ) {
    final var srcRefl = pickReflective(srcCls, beanRefl);
    final var tgtRefl = pickReflective(tgtCls, beanRefl);
    final var match = PairingRules.matchFields(
      List.of(srcRefl.names(srcCls)),
      List.of(tgtRefl.names(tgtCls)),
      Set.of(),
      Set.of()
    );
    for (final var name : match.matched()) {
      final var paired = Reflective.pairedTypes(srcRefl, srcCls, name, tgtRefl, tgtCls, name);
      final var st = paired.source();
      final var tt = paired.target();
      // Descend only into a pure-auto nested pair not already on the current path — the seen guard
      // severs cyclic type graphs (A → B → A) at the second encounter, emitting a single node
      // there.
      if (
        st instanceof Class<?> sc &&
        tt instanceof Class<?> tc &&
        isNestedAutoPair(st, tt, overrides, ownBridges) &&
        seen.add(new TypePair(sc, tc))
      ) {
        collectNested(sc, tc, prefix + name + ".", overrides, ownBridges, beanRefl, out, seen);
        seen.remove(new TypePair(sc, tc));
      } else out.add(fieldNode(prefix + name, prefix + name, st, tt));
    }
    for (final var name : match.unmatchedTargets())
      out.add(new OpticNode.Skipped(prefix + name, OpticNode.Reason.MISSING_SOURCE));
    for (final var name : match.unmatchedSources()) out.add(new OpticNode.UnusedSource(prefix + name));
  }

  /**
   * Build the Iso for {@code (source, target)} and store it in {@code cache}. If {@code
   * topStepsOut} is non-null, also populate it with the per-component FieldSteps for this exact
   * call — used by the top-level entry to derive the patch table.
   */
  private static <S, T> void populateIso(
    final Class<S> source,
    final Class<T> target,
    final Map<TypePair, List<Mapping<?, ?>>> overrides,
    final Reflective beanRefl,
    final Map<TypePair, Iso<?, ?>> cache,
    final Map<String, FieldStep> topStepsOut,
    final NullHint.NullStrategy nullStrategy,
    final Set<TypePair> cyclicPairs,
    final Deque<TypePair> inProgress,
    final OwnBridges ownBridges,
    final SealedCases sealedCases,
    final boolean lenient,
    // True for a mapper with no backward direction, at every depth: it decides which enum pairs
    // convert. `lenient` differs because it applies to the pair the user named and nowhere below.
    final boolean forwardOnly,
    // Introspection collector: non-null only for the top-level pair, where each field decision
    // appends one OpticNode as it is made. Recursive (nested-pair) Iso-building calls pass null;
    // nested dotted paths are captured separately by collectNested from the top-level pair, so
    // the
    // collector is not threaded through the Iso recursion.
    final List<OpticNode> trailOut
  ) {
    final var key = new TypePair(source, target);
    if (cache.containsKey(key)) {
      // Re-entry on an in-progress slot (sentinel == null) means we've found a static cycle in the
      // type graph. Mark this pair AND every ancestor on the recursion stack as cyclic — they're
      // all
      // in the same SCC and must keep the value-level cycle-safe shell at runtime. Acyclic pairs
      // get
      // a plain Iso pass-through, skipping the ~15 ns ThreadLocal + IdentityHashMap probe per hop.
      if (cache.get(key) == null) {
        cyclicPairs.add(key);
        cyclicPairs.addAll(inProgress);
        return;
      }
      // A finished pair asked for again with its fields wanted, which is a case of a sealed root
      // first met below another case, is walked again so its own fields are recorded.
      if (topStepsOut == null) return;
    }
    cache.put(key, null); // reserve slot so cycle-re-entry short-circuits
    inProgress.push(key);
    // The pop lives in finally (at the end of this method) so a thrown IllegalStateException —
    // the strict-bijection guard at the top-level pair — doesn't corrupt the `inProgress` stack.
    // `inProgress` holds the pairs being converted around a pair as it is met, which decide cycle
    // marking and whether a pair's own bridge may stand in for its walk; a leaked frame would
    // count as an ancestor of every pair met afterwards.
    try {
      final var srcRefl = pickReflective(source, beanRefl);
      final var tgtRefl = pickReflective(target, beanRefl);

      final var byTargetName = new LinkedHashMap<String, FieldStep>();
      final var bySourceName = new LinkedHashMap<String, FieldStep>();
      // Strict claims: at most one SameTypedTo / TypedTransformTo / Via / Drop row per (src, tgt)
      // field.
      // Duplicates among these would be genuinely ambiguous, so they fail fast.
      final var claimedTgt = new HashSet<String>();
      final var claimedSrc = new HashSet<String>();
      // Soft claims: telescope-based rows are post-fixups, not exclusive overrides — they mark a
      // field as "consumed by a telescope read/write" so the strict source/target must-be-claimed
      // pass below accepts it, but don't conflict with strict rows or with each other on the same
      // field. Multiple Mapping.to(srcAcc, tgtTelescope) rows reading the same source field, or
      // Mapping.to(srcAcc, tgtTelescope) co-existing with a Mapping.to(srcAcc, tgtAcc), are both
      // OK.
      final var telescopeReadsSrc = new HashSet<String>();
      final var telescopeWritesTgt = new HashSet<String>();
      // The source fields rows read through a path one field hop long, outside a `when`. Such a
      // path reads that one field, as an accessor does, so the field does not pair by name.
      final var pathReadsSrc = new HashSet<String>();
      // Whether a path row of this pair names no first hop on the target, so which target field it
      // writes is not known.
      var unknownTgtWrite = false;
      final var telescopeFixups = new ArrayList<Mapping<?, ?>>();
      // Which source properties the pair drops and writes, and into which targets, as the shared
      // spec refuses a property claimed twice. A write is a row backward reads the property back
      // from: an accessor row, a target path fed by an accessor, and a source path one field hop
      // long.
      final var sourceWrites = new SourceWrites(source.getSimpleName(), target.getSimpleName(), !forwardOnly);
      // Target paths that name no property are told apart by the order they come in.
      var unnamedPaths = 0;

      for (final var rawRow : rowsAt(overrides, key)) {
        // Conditional<A, B>(predicate, inner) wraps a telescope-based row. For soft-claim routing,
        // peel to the inner — Conditional delegates sourceField/targetField/sourceClass/targetClass
        // to inner already, so the metadata is identical, but the telescope-shape checks below need
        // the concrete inner type. The Conditional itself (rawRow) goes into telescopeFixups so
        // applyForward can evaluate the predicate before dispatching the inner's effect.
        final Mapping<?, ?> row = (rawRow instanceof Conditional<?, ?> c) ? c.inner() : rawRow;
        // Normalize raw method names per side — record::name stays "name", bean::getName becomes
        // "name". `sourceField()` returns null on rows whose source is a nested telescope rather
        // than a flat accessor (FromTelescopeTo, TelescopeToTelescope); those branches re-read the
        // first hop name from the telescope below and don't consume srcField, so null here is safe.
        final var rawSrcField = row.sourceField();
        final var srcField = rawSrcField == null ? null : srcRefl.normalize(rawSrcField);
        // Exhaustive routing over the sealed row family: the compiler forces every permit to
        // declare its claim here, so a future row kind cannot silently fall through to the
        // structural tail (or be dropped). Telescope-shaped rows register soft claims and land in
        // telescopeFixups; Drop is terminal; structural rows yield true and fall through to the
        // shared field-claim tail below.
        final boolean structural = switch (row) {
          // TelescopeTo: flat source accessor → nested target telescope.
          // Soft claim on the source field (from srcAcc) AND the target telescope's first hop
          // name.
          // Multiple rows sharing the same source field or top-level target field all compose.
          case TelescopeTo<?, ?, ?> tRow -> {
            telescopeReadsSrc.add(srcField);
            final var firstTgtHop = tRow.targetTelescope().firstHopName();
            if (firstTgtHop != null) telescopeWritesTgt.add(tgtRefl.normalize(firstTgtHop));
            else unknownTgtWrite = true;
            // Backward reads the source property back from the path, at any depth, unless a
            // `when` gates the row, which backward leaves out.
            if (rawRow == row) {
              final var named = tRow.targetTelescope().pathName();
              final var claimed = sourceWrites.write(
                srcField,
                named != null ? named : "an unnamed path (" + ++unnamedPaths + ")"
              );
              if (claimed != null) throw new IllegalArgumentException(claimed);
            }
            telescopeFixups.add(rawRow);
            yield false;
          }
          // FromTelescopeTo: nested source telescope → flat target accessor — soft claim
          // mirror.
          // Soft claim on the target field (from tgtAcc) AND the source telescope's first hop
          // name.
          case FromTelescopeTo<?, ?, ?> fRow -> {
            telescopeWritesTgt.add(tgtRefl.normalize(row.targetField()));
            final var firstSrcHop = fRow.sourceTelescope().firstHopName();
            if (firstSrcHop != null) telescopeReadsSrc.add(srcRefl.normalize(firstSrcHop));
            final var readsOne = firstSrcHop != null && rawRow == row && fRow.sourceTelescope().readsOneProperty();
            if (readsOne) {
              pathReadsSrc.add(srcRefl.normalize(firstSrcHop));
              final var claimed = sourceWrites.write(
                srcRefl.normalize(firstSrcHop),
                tgtRefl.normalize(row.targetField())
              );
              if (claimed != null) throw new IllegalArgumentException(claimed);
            } else if (rawRow == row && fRow.sourceTelescope().stepsThroughProperties()) {
              // A longer path through properties alone is written back through, so it is a write
              // of the path, and of every property it steps through for a drop.
              final var claimed = sourceWrites.write(
                fRow.sourceTelescope().pathName(),
                tgtRefl.normalize(row.targetField())
              );
              if (claimed != null) throw new IllegalArgumentException(claimed);
            }
            // Backward writes through the path, building a null object on the way, so a mapper that
            // converts backward refuses a path through a class no write strategy builds.
            if (!forwardOnly && rawRow == row) {
              final var plan = PathWrites.plan(fRow.sourceTelescope(), source);
              final var unbuildable =
                plan == null ? null : PathWrites.unbuildable(plan, source.getSimpleName(), target.getSimpleName());
              if (unbuildable != null) throw new IllegalArgumentException(unbuildable);
            }
            telescopeFixups.add(rawRow);
            yield false;
          }
          // TelescopeToTelescope (covers both Kind.BROADCAST from Mapping.to and Kind.ZIP from
          // Mapping.zip): both sides are nested telescopes. Recover the top-level src/tgt field
          // names from each telescope's first hop so auto-recursion can build the top-level
          // structure; the post-fixup then overlays the deep leaf (broadcast or positional,
          // depending on kind).
          case TelescopeToTelescope<?, ?, ?> ttRow -> {
            final var firstSrcHop = ttRow.sourceTelescope().firstHopName();
            final var firstTgtHop = ttRow.targetTelescope().firstHopName();
            if (firstSrcHop != null) telescopeReadsSrc.add(srcRefl.normalize(firstSrcHop));
            if (firstTgtHop != null) telescopeWritesTgt.add(tgtRefl.normalize(firstTgtHop));
            else unknownTgtWrite = true;
            telescopeFixups.add(rawRow);
            yield false;
          }
          // Constant / Compute rows are target-injection telescope fixups: claim the target
          // telescope's first hop as written, register the row in telescopeFixups, and let the
          // applyForward pass stamp value (Constant) or supplier.get() (Compute) at the
          // location
          // the telescope navigates to. The flat factories (constant(Accessor, X) /
          // compute(Accessor, Supplier)) wrap the accessor in a single-hop telescope at
          // construction time so this loop sees only one shape per kind — no separate flat
          // handler. Backward direction is a no-op: these rows don't contribute to
          // bySourceName,
          // so the source rebuilder ignores the slot entirely on backward (same retraction
          // semantics as Drop on the source side).
          case Constant<?, ?, ?> cRow -> {
            final var firstTgtHop = cRow.targetTelescope().firstHopName();
            if (firstTgtHop != null) telescopeWritesTgt.add(tgtRefl.normalize(firstTgtHop));
            else unknownTgtWrite = true;
            telescopeFixups.add(rawRow);
            yield false;
          }
          case Compute<?, ?, ?> cpRow -> {
            final var firstTgtHop = cpRow.targetTelescope().firstHopName();
            if (firstTgtHop != null) telescopeWritesTgt.add(tgtRefl.normalize(firstTgtHop));
            else unknownTgtWrite = true;
            telescopeFixups.add(rawRow);
            yield false;
          }
          // Drop rows claim a source field with no target counterpart — they exist to satisfy
          // the
          // strict source-must-be-claimed pass below when one side carries fields the other
          // doesn't.
          case Drop<?, ?, ?> __ -> {
            final var claimed = sourceWrites.drop(srcField);
            if (claimed != null) throw new IllegalArgumentException(claimed);
            if (!claimedSrc.add(srcField)) throw new IllegalArgumentException(
              PairingMessages.duplicateSourceRow(source.getSimpleName(), target.getSimpleName(), srcField)
            );
            // Register a backward-only step under the source name so the source-reconstructor
            // (assembleIso's bySourceName loop) produces a placeholder for the dropped field:
            // null for references, the JLS default for primitives (a null would NPE at the
            // canonical constructor's unboxing). The step is NOT registered under byTargetName,
            // so the forward direction omits the source field from the target map entirely.
            final var droppedType = rawClassOf(srcRefl.genericType(source, srcField));
            bySourceName.put(
              srcField,
              new FieldStep(srcField, null, Placeholders.placeholderIsoFor(droppedType, false))
            );
            if (trailOut != null) trailOut.add(new OpticNode.Skipped(srcField, OpticNode.Reason.DROPPED));
            yield false;
          }
          // Peeled above; Conditional cannot nest (rejected at construction) — unreachable.
          case Conditional<?, ?> __ -> throw new IllegalStateException(
            "Conditional row survived peeling — routing regression in populateIso"
          );
          case SameTypedTo<?, ?, ?> __ -> true;
          case TypedTransformTo<?, ?, ?, ?> __ -> true;
          case ForwardOnlyTransformTo<?, ?, ?, ?> __ -> true;
          case Via<?, ?> __ -> true;
          case ViaClass<?, ?> __ -> true;
          case ViaMethods<?, ?> __ -> true;
        };
        if (!structural) continue;
        final var tgtField = tgtRefl.normalize(row.targetField());
        // Fail fast on duplicate target — two rows targeting the same target field would silently
        // overwrite each other in byTargetName and could produce non-bijective forward/backward
        // (each direction using a different correspondence).
        if (!claimedTgt.add(tgtField)) throw new IllegalArgumentException(
          PairingMessages.duplicateTargetRow(source.getSimpleName(), target.getSimpleName(), tgtField)
        );
        final var written = sourceWrites.write(srcField, tgtField);
        if (written != null) throw new IllegalArgumentException(written);
        claimedSrc.add(srcField);
        final var paired = Reflective.pairedTypes(srcRefl, source, srcField, tgtRefl, target, tgtField);
        final var srcType = paired.source();
        final var tgtType = paired.target();
        // A same-typed to(src, tgt) carries no conversion — it resolves exactly like an
        // auto-matched (here possibly renamed) field, so route it through autoIso. One tested place
        // then supplies identity / null-safe primitive-wrapper / container lifting and the same
        // default-on-null wrapping and "incompatible shapes" rejection the auto path gives. This
        // also closes the LUB-inference hole: javac infers the shared type parameter as the
        // least-upper-bound of the two accessors, so to(Integer-getter, String-getter) compiles and
        // would otherwise identity-pass an Integer into a String setter (ClassCastException at
        // construct). Rows that DO carry user forward/backward functions keep their own leaf Iso.
        final var resolved = resolvedRow(row, srcType, tgtType, new RowSides(source, target, srcField, tgtField));
        final var rowIso =
          row instanceof SameTypedTo<?, ?, ?>
            ? checkedAgainstFixed(
                autoIso(
                  srcType,
                  tgtType,
                  srcField + " → " + tgtField,
                  overrides,
                  beanRefl,
                  cache,
                  nullStrategy,
                  cyclicPairs,
                  inProgress,
                  ownBridges,
                  sealedCases,
                  forwardOnly
                ),
                paired,
                new RowSides(source, target, srcField, tgtField),
                srcField + " → " + tgtField,
                forwardOnly
              )
            : resultChecked(
                fieldIsoOf(resolved, srcType, tgtType, new RowSides(source, target, srcField, tgtField)),
                resolved,
                paired,
                new RowSides(source, target, srcField, tgtField),
                srcRefl,
                tgtRefl,
                forwardOnly
              );
        final var step = new FieldStep(srcField, tgtField, rowIso);
        byTargetName.put(tgtField, step);
        bySourceName.put(srcField, step);
        // A same-typed row is a (possibly renamed) pass-through; a row carrying user
        // forward/backward functions is a transform regardless of the declared field types.
        if (trailOut != null) trailOut.add(
          row instanceof SameTypedTo<?, ?, ?>
            ? fieldNode(tgtField, srcField, srcType, tgtType)
            : new OpticNode.Transformed(srcField, tgtField, simpleTypeName(srcType), simpleTypeName(tgtType))
        );
      }
      // Every write of the pair is recorded once its rows are read, so a fan-out is refused here,
      // before any property the rows leave unpaired.
      final var fannedOut = sourceWrites.fanOutRefusal();
      if (fannedOut != null) throw new IllegalArgumentException(fannedOut);

      final var srcNames = srcRefl.names(source);
      final var srcNameSet = new HashSet<>(List.of(srcNames));
      // A source field no row reads whose same-named target a row reading another source field
      // writes has nowhere to go. Only a pair with rows of its own can have such a target, and the
      // user configured that pair, at whatever depth it is met, so a strict mapper refuses it
      // whatever other rows the pair has. A forward-only mapper leaves it unread, below: `lenient`
      // says so for its own pair, and `forwardOnly` for every pair below it.
      if (!lenient && !forwardOnly) {
        final var kept = new ArrayList<String>();
        for (final var name : srcNames) {
          if (!claimedSrc.contains(name) && !telescopeReadsSrc.contains(name)) kept.add(name);
        }
        final var taken = KeptNames.taken(kept, claimedTgt);
        if (!taken.isEmpty()) {
          final var dropped = topLevelDrop(overrides, source, srcRefl, taken.getFirst());
          throw new IllegalStateException(
            dropped == null
              ? PairingMessages.noSameNameTarget(
                  source.getSimpleName(),
                  target.getSimpleName(),
                  slot(srcRefl),
                  slot(tgtRefl),
                  taken.getFirst()
                )
              : PairingMessages.keptFieldDroppedAtTop(
                  source.getSimpleName(),
                  target.getSimpleName(),
                  slot(srcRefl),
                  slot(tgtRefl),
                  taken.getFirst(),
                  source.getSimpleName(),
                  dropped.sourceField()
                )
          );
        }
      }
      // A target field no row writes pairs with the source field of its name only when no row
      // reads that source field. The rows that read one are those with a source accessor, a
      // drop, and a path one field hop long; a longer path reads below the field and leaves it to
      // pair by name.
      final var tgtNames = tgtRefl.names(target);
      final var readSrc = new HashSet<>(claimedSrc);
      readSrc.addAll(pathReadsSrc);
      final var byName = new HashSet<>(
        PairingRules.matchFields(List.of(srcNames), List.of(tgtNames), readSrc, claimedTgt).matched()
      );
      for (final var name : tgtNames) {
        if (claimedTgt.contains(name)) continue;
        // Telescope-row permissive mode: when ANY telescope row is registered for this pair, a
        // target field nothing pairs with by name gets a NULLING_ISO placeholder. The post-fixup
        // overlays it if a telescope row writes through that field; otherwise the field stays
        // null. Telescope rows can't recover their top-level target field name from a
        // Telescope<B, X> at runtime (generics erased), so we permit the gap instead of failing on
        // every nested write. Two cases still refuse: a pair with no telescope row, and a target
        // field whose same-named source a row reads, which only a row naming that field lets
        // through.
        if (!byName.contains(name)) {
          // Lenient on nested auto-recursed pairs: a pair recursed into from computeAutoIso (no
          // topStepsOut) was never explicitly configured by the user — it was visited because a
          // parent field's genericType is reflectable. An unmatched target field there should stay
          // at its JLS default rather than abort the top-level construction. Strictness is kept
          // where the mapper's own fields are decided (topStepsOut present): the pair the user
          // asked for, or a case of the two sealed interfaces the user asked for. Missing fields
          // there ARE a configuration mistake worth surfacing.
          final var isNested = topStepsOut == null;
          // A target field left unpaired because a row reads the source field of its name sits at
          // a pair with rows of its own, which the user configured, so a strict mapper refuses it
          // at any depth unless a row writes it: a path, constant or compute row naming it, at this
          // pair or through a path from a pair above.
          final var unwrittenNamesake =
            srcNameSet.contains(name) &&
            !lenient &&
            !forwardOnly &&
            !telescopeWritesTgt.contains(name) &&
            !unknownTgtWrite &&
            !writtenThroughPath(overrides, target, name);
          // `lenient` is set by `resolveForward` — forward-only mappers don't run `backward()`,
          // so the bijection invariant doesn't apply and unmatched target fields silently take
          // the JLS default rather than abort construction. Matches MapStruct's default behaviour
          // and removes "13 drops + 2 constants for a 3-field rename" friction on small-DTO →
          // large-entity migration shapes.
          if (!unwrittenNamesake && (!telescopeFixups.isEmpty() || isNested || lenient)) {
            // Telescope-row placeholder for a target field with no same-name source. Three cases,
            // type-driven:
            //   - field type is a record AND a telescope row claims it as a first hop: allocate a
            //     recursive default-tree instance so the post-fixup overlay
            //     (`tgtTelescope.set(t, value)`) can descend into a non-null intermediate. Records
            //     only for v1.0; beans need a no-arg ctor or builder which isn't always present —
            //     deferred to v1.1.
            //   - field type is a primitive: return the JLS default (0 / false / etc.) so
            // canonical-
            //     ctor reflection doesn't NPE unboxing a null Object.
            //   - everything else: NULLING_ISO (null reference, unchanged behavior).
            final var fieldType = rawClassOf(tgtRefl.genericType(target, name));
            byTargetName.putIfAbsent(
              name,
              new FieldStep(null, name, Placeholders.placeholderIsoFor(fieldType, telescopeWritesTgt.contains(name)))
            );
            // Only truly-unpopulated fields are MISSING_SOURCE. A field a fixup writes (constant /
            // compute / nested-telescope row) is registered in telescopeWritesTgt and gets a real
            // value from the post-fixup overlay — it is populated, not missing, so it is not
            // skipped.
            if (trailOut != null && !telescopeWritesTgt.contains(name)) trailOut.add(
              new OpticNode.Skipped(name, OpticNode.Reason.MISSING_SOURCE)
            );
            continue;
          }
          throw new IllegalStateException(
            srcNameSet.contains(name)
              ? PairingMessages.sameNameSourceRead(
                  source.getSimpleName(),
                  target.getSimpleName(),
                  slot(tgtRefl),
                  slot(srcRefl),
                  name
                )
              : PairingMessages.noSameNameSource(
                  source.getSimpleName(),
                  target.getSimpleName(),
                  slot(tgtRefl),
                  slot(srcRefl),
                  name
                )
          );
        }
        final var autoPaired = Reflective.pairedTypes(srcRefl, source, name, tgtRefl, target, name);
        final var autoSrcType = autoPaired.source();
        final var autoTgtType = autoPaired.target();
        final var step = new FieldStep(
          name,
          name,
          checkedAgainstFixed(
            autoIso(
              autoSrcType,
              autoTgtType,
              name,
              overrides,
              beanRefl,
              cache,
              nullStrategy,
              cyclicPairs,
              inProgress,
              ownBridges,
              sealedCases,
              forwardOnly
            ),
            autoPaired,
            new RowSides(source, target, name, name),
            name,
            forwardOnly
          )
        );
        byTargetName.put(name, step);
        bySourceName.put(name, step);
        claimedSrc.add(name);
        if (trailOut != null && isNestedAutoPair(autoSrcType, autoTgtType, overrides, ownBridges)) {
          final var seen = new HashSet<TypePair>();
          seen.add(new TypePair((Class<?>) autoSrcType, (Class<?>) autoTgtType));
          collectNested(
            (Class<?>) autoSrcType,
            (Class<?>) autoTgtType,
            name + ".",
            overrides,
            ownBridges,
            beanRefl,
            trailOut,
            seen
          );
        } else if (trailOut != null) {
          trailOut.add(fieldNode(name, name, autoSrcType, autoTgtType));
          addDispatched(trailOut, name, autoSrcType, autoTgtType, sealedCases);
        }
      }

      for (final var name : srcNames) {
        if (claimedSrc.contains(name)) continue;
        // Telescope-read source without a same-name target consumer: register a NULLING_ISO
        // backward-only placeholder so the source rebuilder produces null for this field; the
        // backward post-fixup will fill the real value from the target telescope.
        if (telescopeReadsSrc.contains(name)) {
          bySourceName.putIfAbsent(
            name,
            new FieldStep(name, null, Placeholders.placeholderIsoFor(rawClassOf(srcRefl.genericType(source, name)), false))
          );
          continue;
        }
        // Same permissive mode as the target side: when telescope rows are present, this is a
        // nested auto-recursed pair, OR the resolution is forward-only (lenient), source fields
        // with no consumer fall back to a NULLING placeholder rather than failing.
        if (!telescopeFixups.isEmpty() || topStepsOut == null || lenient) {
          bySourceName.putIfAbsent(name, new FieldStep(name, null, Placeholders.NULLING_ISO));
          if (trailOut != null) trailOut.add(new OpticNode.UnusedSource(name));
          continue;
        }
        throw new IllegalStateException(
          PairingMessages.noSameNameTarget(
            source.getSimpleName(),
            target.getSimpleName(),
            slot(srcRefl),
            slot(tgtRefl),
            name
          )
        );
      }

      // A value converted for a bean property's own type is written through its setter, so a
      // setter that cannot take that type refuses the pair here, on the side each direction the
      // mapper runs writes.
      refuseUnwritableSetters(target, tgtRefl.writerFor(target), byTargetName, true);
      if (!forwardOnly) refuseUnwritableSetters(source, srcRefl.writerFor(source), bySourceName, false);
      final Iso<S, T> baseIso = assembleIso(source, target, srcRefl, tgtRefl, byTargetName, bySourceName);
      cache.put(
        key,
        telescopeFixups.isEmpty() ? baseIso : TelescopeFixups.wrap(baseIso, telescopeFixups, srcRefl, source)
      );
      if (topStepsOut != null) {
        // Patch writes the partial's value of a target property a source path fills back through
        // the path, after the rest of the source is rebuilt, in the order the rows came.
        for (final var fixup : telescopeFixups) {
          if (!(fixup instanceof FromTelescopeTo<?, ?, ?> fRow)) continue;
          final var tgtField = tgtRefl.normalize(fRow.targetField());
          final var step = byTargetName.remove(tgtField);
          byTargetName.put(
            tgtField,
            new FieldStep(
              step == null ? null : step.sourceName,
              tgtField,
              step == null ? Placeholders.NULLING_ISO : step.iso,
              TelescopeFixups.patchOverlay(fRow, source, srcRefl)
            )
          );
        }
        topStepsOut.putAll(byTargetName);
      }
    } finally {
      inProgress.pop();
    }
  }

  /**
   * Build the {@link Iso} between two sealed interfaces and store it in {@code cache}: each case of
   * the source pairs with a case of the target, as {@link SealedCases#pairsOf} decides, and a value
   * converts through the conversion of its own case, chosen through {@link Match}.
   *
   * <p>{@code casesOut} is non-null for the pair a mapper is built for. Its cases are then the
   * mapper's own: a case converted through its own bridge keeps that bridge's patch, and any other
   * is walked with its fields recorded, strictly, as the pair a mapper is built for is. Each case's
   * mapper is added to {@code casesOut}, and each pairing to {@code trailOut}. Below the mapper's
   * own pair, a case converts as any nested pair does.
   */
  @SuppressWarnings("unchecked")
  private static void populateSealed(
    final Class<?> source,
    final Class<?> target,
    final Map<TypePair, List<Mapping<?, ?>>> overrides,
    final Reflective beanRefl,
    final Map<TypePair, Iso<?, ?>> cache,
    final NullHint.NullStrategy nullStrategy,
    final Set<TypePair> cyclicPairs,
    final Deque<TypePair> inProgress,
    final OwnBridges ownBridges,
    final SealedCases sealedCases,
    final boolean lenient,
    final boolean forwardOnly,
    final List<Mapper.CaseEntry> casesOut,
    final List<OpticNode> trailOut
  ) {
    final var key = new TypePair(source, target);
    if (cache.containsKey(key)) {
      if (cache.get(key) == null) {
        cyclicPairs.add(key);
        cyclicPairs.addAll(inProgress);
        return;
      }
      // A finished pair asked for again with its cases wanted, which is a sealed case of the
      // mapper's own pair first met below another case, is built again so its cases are recorded.
      if (casesOut == null) return;
    }
    cache.put(key, null);
    inProgress.push(key);
    try {
      var forward = Match.<Object, Object>of((Class<Object>) source);
      var backward = Match.<Object, Object>of((Class<Object>) target);
      final var backwardCases = new HashSet<Class<?>>();
      for (final var pair : sealedCases.pairsOf(key, forwardOnly)) {
        final Iso<Object, Object> caseIso;
        try {
          if (casesOut == null) caseIso = eraseIso(
            autoIso(
              pair.source,
              pair.target,
              pair.source.getSimpleName(),
              overrides,
              beanRefl,
              cache,
              nullStrategy,
              cyclicPairs,
              inProgress,
              ownBridges,
              sealedCases,
              forwardOnly
            )
          );
          else {
            final var entry = ownCase(
              pair,
              overrides,
              beanRefl,
              cache,
              nullStrategy,
              cyclicPairs,
              inProgress,
              ownBridges,
              sealedCases,
              lenient,
              forwardOnly
            );
            casesOut.add(entry.getKey());
            caseIso = entry.getValue();
          }
        } catch (final IllegalStateException | IllegalArgumentException refused) {
          throw passedOverRefusal(key, pair, refused, ownBridges, sealedCases, inProgress);
        }
        forward = forward.when((Class<Object>) pair.source, caseIso::to);
        // A mapper with no backward direction may pair two cases with one case of the target; the
        // first stands for it in the backward table, which nothing reads.
        if (backwardCases.add(pair.target)) backward = backward.when((Class<Object>) pair.target, caseIso::from);
        if (trailOut != null) trailOut.add(new OpticNode.Dispatched("", pair.source, pair.target));
      }
      final var to = forward.exhaustive();
      final var from = forwardOnly ? backward.partial() : backward.exhaustive();
      cache.put(
        key,
        Iso.of(value -> value == null ? null : to.apply(value), value -> value == null ? null : from.apply(value))
      );
    } finally {
      inProgress.pop();
    }
  }

  /**
   * {@code refused}, from converting a case paired through its own bridge, said again with the
   * bridge the walk passed over and why, since the walk then matched the case's properties by name
   * where the bridge would have converted them its own way. Any other refusal is returned as it is.
   */
  private static RuntimeException passedOverRefusal(
    final TypePair roots,
    final TypePair pair,
    final RuntimeException refused,
    final OwnBridges ownBridges,
    final SealedCases sealedCases,
    final Deque<TypePair> inProgress
  ) {
    if (!sealedCases.pairedThroughBridge(pair)) return refused;
    final var reason = ownBridges.passedOver(pair, inProgress);
    if (reason == null) return refused;
    return new IllegalStateException(
      PairingMessages.caseBridgePassedOver(
        roots.source.getSimpleName(),
        roots.target.getSimpleName(),
        pair.source.getSimpleName(),
        pair.target.getSimpleName(),
        reason,
        refused.getMessage()
      ),
      refused
    );
  }

  /**
   * One case of the sealed pair a mapper is built for: the mapper the case's values reach through
   * {@code patch} and {@code into}, and the conversion its values take.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Map.Entry<Mapper.CaseEntry, Iso<Object, Object>> ownCase(
    final TypePair pair,
    final Map<TypePair, List<Mapping<?, ?>>> overrides,
    final Reflective beanRefl,
    final Map<TypePair, Iso<?, ?>> cache,
    final NullHint.NullStrategy nullStrategy,
    final Set<TypePair> cyclicPairs,
    final Deque<TypePair> inProgress,
    final OwnBridges ownBridges,
    final SealedCases sealedCases,
    final boolean lenient,
    final boolean forwardOnly
  ) {
    final Class source = pair.source;
    final Class target = pair.target;
    final var decision = PAIRING.decidePair(source, target, source.getSimpleName(), forwardOnly);
    final var bridge = decision instanceof PairDecision.RecursePair ? ownBridges.bridgeFor(pair, inProgress) : null;
    final Mapper<?, ?> mapper;
    final Iso<Object, Object> iso;
    if (decision instanceof PairDecision.DispatchCases) {
      final var cases = new ArrayList<Mapper.CaseEntry>();
      populateSealed(
        source,
        target,
        overrides,
        beanRefl,
        cache,
        nullStrategy,
        cyclicPairs,
        inProgress,
        ownBridges,
        sealedCases,
        lenient,
        forwardOnly,
        cases,
        null
      );
      iso = eraseIso(lazyCacheIso(cache, pair, !cyclicPairs.contains(pair), ownBridges));
      mapper = Mapper.ofCases((Iso) cache.get(pair), source, target, cases, List.of());
    } else if (bridge != null) {
      iso = eraseIso(isoOf(pair, bridge, ownBridges));
      mapper = Mapper.ofPatcher(
        (Iso) iso,
        source,
        target,
        Telescope.bridgePatch((Telescope) bridge),
        List.of(pickReflective(target, beanRefl).names(target))
      );
    } else if (decision instanceof PairDecision.RecursePair) {
      final var steps = new LinkedHashMap<String, FieldStep>();
      populateIso(
        source,
        target,
        overrides,
        beanRefl,
        cache,
        steps,
        nullStrategy,
        cyclicPairs,
        inProgress,
        ownBridges,
        sealedCases,
        lenient,
        forwardOnly,
        null
      );
      iso = eraseIso(lazyCacheIso(cache, pair, !cyclicPairs.contains(pair), ownBridges));
      mapper = Mapper.ofIso(
        (Iso) cache.get(pair),
        source,
        target,
        patchTableOf(steps, source),
        List.of(),
        pickReflective(source, beanRefl)
      );
    } else {
      // A case that is no record, bean or sealed interface, such as an enum, converts as the shared
      // decision says and has no fields to patch.
      iso = eraseIso(
        autoIso(
          source,
          target,
          source.getSimpleName(),
          overrides,
          beanRefl,
          cache,
          nullStrategy,
          cyclicPairs,
          inProgress,
          ownBridges,
          sealedCases,
          forwardOnly
        )
      );
      // An enum constant is its own rebuild, and has no fields a partial could overlay.
      final BiFunction<Object, Object, Object> rebuild = (base, partial) -> base;
      mapper = source.isEnum()
        ? Mapper.ofPatcher((Iso) iso, source, target, rebuild, List.of())
        : Mapper.ofIso((Iso) iso, source, target, Map.of(), List.of(), pickReflective(source, beanRefl));
    }
    return Map.entry(new Mapper.CaseEntry(source, target, mapper), iso);
  }

  // ---------- Per-component auto resolution ----------

  private static Iso<?, ?> autoIso(
    final Type srcType,
    final Type tgtType,
    final String componentName,
    final Map<TypePair, List<Mapping<?, ?>>> overrides,
    final Reflective beanRefl,
    final Map<TypePair, Iso<?, ?>> cache,
    final NullHint.NullStrategy nullStrategy,
    final Set<TypePair> cyclicPairs,
    final Deque<TypePair> inProgress,
    final OwnBridges ownBridges,
    final SealedCases sealedCases,
    final boolean forwardOnly
  ) {
    final var raw = computeAutoIso(
      srcType,
      tgtType,
      componentName,
      overrides,
      beanRefl,
      cache,
      nullStrategy,
      cyclicPairs,
      inProgress,
      ownBridges,
      sealedCases,
      forwardOnly
    );
    // DEFAULT strategy wraps EVERY auto-recursed per-component Iso uniformly: scalar identity,
    // recursive record/bean pair, lifted container, cross-Optional bridge. When the source value
    // is null, the wrapper substitutes the per-leaf-type default from NullDefaults#defaultFor so
    // bean-side targets see a usable value rather than null. Non-DEFAULT strategy short-circuits
    // to the raw Iso for a no-op cost.
    return nullStrategy == NullHint.NullStrategy.DEFAULT ? wrapDefaultOnNull(raw, tgtType) : raw;
  }

  /**
   * {@code iso}, or a slot checking the value written into each side against the class that side
   * fixes the property's type variable to, where the pairing hands the value across unchanged and
   * the shared rules call for it ({@link PairingRules#fixedFit}). A value given type arguments that
   * cannot be shown to fit is refused here, while the mapper is built. {@code iso} itself is
   * returned where nothing is checked, so an identity slot keeps the identity short-circuit.
   */
  private static Iso<?, ?> checkedAgainstFixed(
    final Iso<?, ?> iso,
    final PairedTypes<Type> paired,
    final RowSides sides,
    final String componentName,
    final boolean forwardOnly
  ) {
    if (!paired.handedAcross()) return iso;
    final var declared = paired.source();
    final var intoTarget = fixedClass(
      PAIRING.fixedFit(declared, paired.sourceMember(), paired.targetMember(), componentName)
    );
    final var intoSource = forwardOnly
      ? null
      : fixedClass(PAIRING.fixedFit(declared, paired.targetMember(), paired.sourceMember(), componentName));
    if (intoTarget == null && intoSource == null) return iso;
    // Only a single value is checked, and a single value handed across unchanged is the identity
    // slot: its declared type names a variable, which no default-on-null wrapper has a value for,
    // and a type given arguments is either shown to fit or refused above. The check takes the
    // slot's place, and the composed rebuild reads it as an identity read with a check on it.
    return new FixedCheck(intoTarget, sides.target(), sides.tgtField(), intoSource, sides.source(), sides.srcField());
  }

  /**
   * {@code iso}, a conversion of the row's own, with its result checked against the class the
   * property takes before it is written: the class the member the property's type chooses takes on
   * each side ({@link Beans#writtenClass}), or, where narrower, the class that side fixes the
   * property's type variable to ({@link #fixedBy}). The conversion is typed by its functions, which
   * nothing here can read, so the result's own class is all that says whether the property takes
   * it. A side whose property takes anything checks nothing, and a forward-only mapper writes no
   * source.
   *
   * <p>A {@code via} row's mapper names the class it returns on each side, so a mapper no result of
   * which the property can take is refused here, while the mapper is built.
   *
   * <p>The value a transform row's conversion is handed is checked too, where the row reads the
   * property as a class narrower than its getter returns ({@link #narrowedRead}): a property
   * declared with a type variable, read by a row typed by the class's type arguments, or through a
   * subclass that fixes the variable. The getter returns the variable's erasure, which nothing
   * checks against the narrower class, so the value's class is all that says whether the conversion
   * takes it.
   */
  @SuppressWarnings("unchecked")
  private static Iso<?, ?> resultChecked(
    final Iso<?, ?> iso,
    final Mapping<?, ?> row,
    final PairedTypes<Type> paired,
    final RowSides sides,
    final Reflective srcRefl,
    final Reflective tgtRefl,
    final boolean forwardOnly
  ) {
    final var targetWriter = tgtRefl.writerFor(sides.target());
    final var sourceWriter = srcRefl.writerFor(sides.source());
    final var targetDeclared = tgtRefl.genericType(sides.target(), sides.tgtField());
    final var sourceDeclared = srcRefl.genericType(sides.source(), sides.srcField());
    final var fixedTarget = fixedBy(paired.target(), targetDeclared, paired.targetMember());
    final var fixedSource = forwardOnly ? null : fixedBy(paired.source(), sourceDeclared, paired.sourceMember());
    final var nested = switch (row) {
      case Via<?, ?> via -> via.nested();
      // A via row of two methods reads them only while the runtime path builds a mapper.
      case ViaMethods<?, ?> methods -> methods.resolved().nested();
      default -> null;
    };
    if (nested != null) {
      refuseResultUnfit(sides, "target", sides.tgtField(), nested.targetClass(), fixedTarget);
      refuseResultUnfit(sides, "source", sides.srcField(), nested.sourceClass(), fixedSource);
    }
    final var intoTarget = narrower(Beans.writtenClass(targetWriter, sides.target(), sides.tgtField()), fixedTarget);
    final var intoSource = forwardOnly
      ? null
      : narrower(Beans.writtenClass(sourceWriter, sides.source(), sides.srcField()), fixedSource);
    final var fromSource = switch (row) {
      case TypedTransformTo<?, ?, ?, ?> r -> narrowedRead(r.src(), sourceDeclared);
      case ForwardOnlyTransformTo<?, ?, ?, ?> r -> narrowedRead(r.src(), sourceDeclared);
      default -> null;
    };
    final var fromTarget =
      !forwardOnly && row instanceof TypedTransformTo<?, ?, ?, ?> r ? narrowedRead(r.tgt(), targetDeclared) : null;
    if (intoTarget == null && intoSource == null && fromSource == null && fromTarget == null) return iso;
    return new ResultCheck(
      (Iso<Object, Object>) iso,
      intoTarget,
      sides.target(),
      sides.tgtField(),
      Beans.writtenNullValue(targetWriter, sides.target(), sides.tgtField()),
      intoSource,
      sides.source(),
      sides.srcField(),
      Beans.writtenNullValue(sourceWriter, sides.source(), sides.srcField()),
      fromSource,
      fromTarget
    );
  }

  /**
   * The class a row reads a property as through {@code accessor}, where it is narrower than the
   * erasure of {@code declared}, the type the property's record component or getter is declared
   * with, which is the class a read of it returns: the erasure of the type argument the row's class
   * gives a property declared with a type variable, which the row's functions take and nothing
   * checked when the property was read. Null where the row reads the property as that class or a
   * supertype of it.
   */
  private static Class<?> narrowedRead(final Telescope.Accessor<?, ?> accessor, final Type declared) {
    return LambdaIntrospection.narrowedReturnOf(accessor, (Class<?>) PROPS.erasure(declared));
  }

  /**
   * The class a side fixes a property's type variable to, where a pairing reads the property as
   * {@code read}, its accessor declares it as {@code declared}, and the side's class gives it
   * {@code member}, a class narrower than either: a property a generic superclass declares with a
   * variable the side's class fixes. A pair read as declared reads it wider than the member; a pair
   * read as members reads it as the member, and the accessor still declares it with the variable,
   * whose erasure is what the member writing it takes. Null where the member's class is no narrower
   * than either.
   */
  private static Class<?> fixedBy(final Type read, final Type declared, final Type member) {
    final var memberClass = (Class<?>) PROPS.erasure(member);
    for (final var wider : List.of(read, declared)) {
      final var widerClass = (Class<?>) PROPS.erasure(wider);
      if (widerClass != memberClass && widerClass.isAssignableFrom(memberClass)) return boxed(memberClass);
    }
    return null;
  }

  /** The narrower of two classes a value is checked against, either of which may be null. */
  private static Class<?> narrower(final Class<?> written, final Class<?> fixed) {
    if (fixed == null) return written;
    return written == null || written.isAssignableFrom(fixed) ? fixed : written;
  }

  /**
   * Refuses a {@code via} row whose mapper returns {@code mapperClass} on the side it writes {@code
   * property} of, when the side fixes the property to {@code fixed} and no value of the mapper's
   * class can be an instance of it: every write would fail.
   */
  private static void refuseResultUnfit(
    final RowSides sides,
    final String side,
    final String property,
    final Class<?> mapperClass,
    final Class<?> fixed
  ) {
    if (fixed == null || mayBeInstance(boxed(mapperClass), fixed)) return;
    throw new IllegalArgumentException(
      PairingMessages.viaResultNeverFits(
        sides.source().getSimpleName(),
        sides.target().getSimpleName(),
        side,
        property,
        mapperClass.getSimpleName(),
        fixed.getSimpleName()
      )
    );
  }

  /**
   * The class {@code fit} checks a value against, or null where it checks none; a refusal is thrown
   * as the shared rules word it.
   */
  private static Class<?> fixedClass(final FixedFit<Type> fit) {
    return switch (fit) {
      case FixedFit.Fits<Type> __ -> null;
      case FixedFit.Refused<Type> refused -> throw new IllegalStateException(refused.message());
      case FixedFit.Check<Type> check -> (Class<?>) check.fixed();
    };
  }

  private static Iso<?, ?> computeAutoIso(
    final Type srcType,
    final Type tgtType,
    final String componentName,
    final Map<TypePair, List<Mapping<?, ?>>> overrides,
    final Reflective beanRefl,
    final Map<TypePair, Iso<?, ?>> cache,
    final NullHint.NullStrategy nullStrategy,
    final Set<TypePair> cyclicPairs,
    final Deque<TypePair> inProgress,
    final OwnBridges ownBridges,
    final SealedCases sealedCases,
    final boolean forwardOnly
  ) {
    // Every branch DECISION routes through the shared pairing spec — this method only maps each
    // decision to its runtime Iso. Branch rationale (kind discriminators, scalar exclusions,
    // ordering) lives on PairingRules#decidePair, consumed identically by the compile-time
    // verifier so what constructs here and what compiles there cannot drift.
    final var decision = PAIRING.decidePair(srcType, tgtType, componentName, forwardOnly);

    // (a) Same type, and not a container the allocation table rebuilds → identity Iso. A
    //     container of one declared type takes the copy in (a.2) instead.
    if (decision instanceof PairDecision.Identity) return Iso.identity();

    // (a.1) Primitive ↔ wrapper pair → autobox / unbox via JLS-default-safe Iso. Forward
    // unboxes (null-safe — substitutes the primitive default to avoid NPE on the wrapper-to-
    // primitive setter); backward boxes via the wrapper's static valueOf. Matches MapStruct's
    // behaviour for these pairs (it silently autoboxes and uses 0/false/etc. for null).
    if (decision instanceof PairDecision.PrimitiveWrapper) {
      return primitiveWrapperIso((Class<?>) srcType, (Class<?>) tgtType);
    }

    // (a.2) Same-kind Collection / Map pair whose elements need no conversion, in one of two
    //       shapes. One declared type on both sides takes ContainerCopy.of, the shallow copy a
    //       generated bridge makes too, so the target does not share the source's container. A
    //       pair written without type arguments on both sides, which is two classes declaring no
    //       type parameters that fix the same element types (`class ImageUrls extends
    //       ArrayList<ImageUrl>`), or a generic class or interface used raw on either side, is
    //       copied with `addAll` / `putAll` into a fresh container: a concrete class through its
    //       own allocator, an interface as the default implementation the shared allocation table
    //       names for its family. The element-type, kind-discriminator and allocability gates live
    //       on the shared spec; the decision only fires when the copy is buildable.
    if (decision instanceof PairDecision.CollectionCopy) {
      return ContainerLifts.collectionCopyIso(srcType, tgtType);
    }
    if (decision instanceof PairDecision.MapCopy) {
      return ContainerLifts.mapCopyIso(srcType, tgtType);
    }

    // (a.1b) Two different enums whose constants line up by name in every direction this mapper
    //        converts → constant to constant by name, null to null.
    if (decision instanceof PairDecision.EnumByName) {
      return enumByNameIso((Class<?>) srcType, (Class<?>) tgtType);
    }

    // (b) Both reflectable (record or bean) → recurse, return cache-reading Iso so cycles work.
    if (decision instanceof PairDecision.RecursePair) {
      // A pair given type arguments converts as its raw classes, which read each other's type
      // variables by position, so the arguments name no pair of their own.
      final var srcCls = rawClassOf(srcType);
      final var tgtCls = rawClassOf(tgtType);
      final var subKey = new TypePair(srcCls, tgtCls);
      // A bridge is decided where the pair is met, from the pairs being converted around it, and is
      // never stored in the cache: a walked pair on a cycle reads the cache when it runs, so a
      // bridge stored there would stand in for the walk on the cycle too.
      final var own = ownBridges.isoFor(subKey, inProgress);
      if (own != null) return own;
      // Nested recursion — `isNested` (no topStepsOut) already triggers the lenient gate
      // for unmatched fields regardless of the outer call's strictness. Pass `false` here so the
      // lenient flag's meaning stays anchored to the user-facing top-level call (mapperForward).
      populateIso(
        srcCls,
        tgtCls,
        overrides,
        beanRefl,
        cache,
        null,
        nullStrategy,
        cyclicPairs,
        inProgress,
        ownBridges,
        sealedCases,
        false,
        forwardOnly,
        null
      );
      return lazyCacheIso(cache, subKey, !cyclicPairs.contains(subKey), ownBridges);
    }

    // (b.1) Two sealed interfaces → each value converts through the pair its case belongs to. The
    //       dispatch is cached like a walked pair, so a case that holds its own root reads it back.
    if (decision instanceof PairDecision.DispatchCases) {
      final var srcCls = rawClassOf(srcType);
      final var tgtCls = rawClassOf(tgtType);
      populateSealed(
        srcCls,
        tgtCls,
        overrides,
        beanRefl,
        cache,
        nullStrategy,
        cyclicPairs,
        inProgress,
        ownBridges,
        sealedCases,
        false,
        forwardOnly,
        null,
        null
      );
      final var subKey = new TypePair(srcCls, tgtCls);
      return lazyCacheIso(cache, subKey, !cyclicPairs.contains(subKey), ownBridges);
    }

    // (c.1) Cross-paradigm Optional bridge — one side is Optional<X>, the other is a possibly-null
    //       scalar/record/bean. Common case: record uses Optional<Address> while the JPA-mapped
    //       entity uses a nullable AddressEmbeddable. Lift the element conversion through
    //       Iso.liftOptionalToNullable so Optional.empty() ↔ null and Optional.of(x) ↔ to(x).
    //
    //       Element-level recursion passes PROPAGATE deliberately: NullStrategy.DEFAULT is a
    //       field-level semantic (matches MapStruct SET_TO_DEFAULT). Wrapping the element-level
    //       Iso would (1) double-wrap when the outer autoIso wraps the lifted result, and (2)
    //       corrupt the {@code .reverse()} on the second branch — coalesceForward's documented
    //       non-bijection at the null/default boundary would land on the BACKWARD direction post-
    //       reverse. Field-level wrap fires once at the outer autoIso return; that's enough.
    if (decision instanceof PairDecision.OptionalToNullable<Type> d) {
      final var elementIso = autoIso(
        d.elementSrc(),
        d.elementTgt(),
        componentName + "[*]",
        overrides,
        beanRefl,
        cache,
        NullHint.NullStrategy.PROPAGATE,
        cyclicPairs,
        inProgress,
        ownBridges,
        sealedCases,
        forwardOnly
      );
      return Iso.liftOptionalToNullable(eraseIso(elementIso));
    }
    if (decision instanceof PairDecision.NullableToOptional<Type> d) {
      final var elementIso = autoIso(
        d.elementSrc(),
        d.elementTgt(),
        componentName + "[*]",
        overrides,
        beanRefl,
        cache,
        NullHint.NullStrategy.PROPAGATE,
        cyclicPairs,
        inProgress,
        ownBridges,
        sealedCases,
        forwardOnly
      );
      return Iso.liftOptionalToNullable(eraseIso(elementIso)).reverse();
    }

    // (c) Both same-kind containers → recurse on the element TYPE so nested containers work
    //     (List<Optional<X>>, Optional<List<X>>, Map<K, List<X>>, etc.). Scalar/record/container
    //     elements all dispatch through this same autoIso recursion. Element-level recursion
    //     passes PROPAGATE — see the cross-Optional branch above for the rationale.
    if (decision instanceof PairDecision.LiftContainer<Type> d) {
      final var elementIso = autoIso(
        d.src().elementType(),
        d.tgt().elementType(),
        componentName + "[*]",
        overrides,
        beanRefl,
        cache,
        NullHint.NullStrategy.PROPAGATE,
        cyclicPairs,
        inProgress,
        ownBridges,
        sealedCases,
        forwardOnly
      );
      // The lifts consult MhIso.liftCollection/liftMap, which sharpen to a MethodHandle loop when
      // the
      // element is a composed-handle leaf. For an acyclic nested pair `elementIso` already IS that
      // leaf (lazyCacheIso hands the concrete leaf through); a cyclic or non-leaf nested pair is
      // the
      // null-guarding lazyCacheIso proxy, and a scalar element is an identity/primitive Iso. The
      // lifts
      // see the right shape directly — no extra reach-through — and treat every non-leaf shape the
      // same (plain Java loop).
      return switch (d.src().kind()) {
        case LIST -> ContainerLifts.liftListIntoTargetRaw(eraseIso(elementIso), srcType, tgtType);
        case SET -> ContainerLifts.liftSetIntoTargetRaw(eraseIso(elementIso), srcType, tgtType);
        case MAP_VALUES -> ContainerLifts.liftMapIntoTargetRaw(eraseIso(elementIso), srcType, tgtType);
        // Optional is final; no subclasses, no allocator needed.
        case OPTIONAL -> Iso.liftOptional(eraseIso(elementIso));
        // Settled against the other side of the pair before the decision was built, so a view
        // still carrying it here means the pairing stopped doing that.
        case COLLECTION -> throw new IllegalStateException(
          "a Collection-declared container reached the lift without taking a shape"
        );
      };
    }

    throw new IllegalStateException(((PairDecision.Incompatible<Type>) decision).message());
  }

  /**
   * Apply the {@code source == null → typeDefault} substitution to a per-field Iso. Delegates to
   * {@link Iso#coalesceForward(Iso, Object)} — the lattice primitive that explicitly documents the
   * deliberate non-bijection at the null/default boundary (the standard Iso round-trip law cannot
   * hold there because the asymmetry IS the MapStruct {@code SET_TO_DEFAULT} semantic). When the
   * default is {@code null} (records, beans, custom types — anything {@link
   * NullDefaults#defaultFor} returns {@code null} for), the wrap is skipped entirely as a no-op
   * cost optimization.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> wrapDefaultOnNull(final Iso<?, ?> inner, final Type tgtType) {
    final var defaultValue = NullDefaults.defaultFor(tgtType);
    if (defaultValue == null) return inner;
    final Iso<Object, Object> erased = (Iso) inner;
    return Iso.coalesceForward(erased, defaultValue);
  }

  /**
   * The leaf-level {@link Iso} contributed by an override row. Pattern-matches on the sealed
   * permitted records so the {@code fieldIso()} accessor stays package-private and never leaks the
   * internal {@link Iso} type out of {@link Mapping}'s public surface.
   *
   * <p>For {@link Via} rows, the user-supplied mapper may be at <em>element-level</em> ({@code
   * Mapper<UserEntity, UserDto>} fed to a {@code List<UserEntity> ↔ List<UserDto>} accessor pair)
   * or at <em>accessor-level</em> ({@code Mapper<List<UserEntity>, List<UserDto>>} fed to the same
   * pair). The shape mismatch is detected by comparing the accessor's container shape against the
   * mapper's source/target classes; when the mapper's classes match the container element type, the
   * Iso is lifted through the matching container ({@code List} / {@code Set} / {@code Optional} /
   * {@code Map} values). Otherwise the element Iso is used as-is.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> fieldIsoOf(
    final Mapping<?, ?> row,
    final Type srcType,
    final Type tgtType,
    final RowSides sides
  ) {
    // Inline the contributed leaf-level Iso for each row variant. Reading the public components
    // directly keeps Iso (internal) out of the mapping types' public signatures — so the mapping
    // types stay portable across packages without needing @SuppressWarnings("exports").
    return switch (row) {
      // SameTypedTo carries no conversion; populateIso resolves it through autoIso (identity /
      // primitive-wrapper / container lifting / incompatible-shape rejection), so it must not reach
      // here. The guard surfaces a routing regression with a clear class name, like the cases
      // below.
      case SameTypedTo<?, ?, ?> __ -> throw new IllegalStateException(
        "SameTypedTo row should be resolved via autoIso, not fieldIsoOf"
      );
      case TypedTransformTo<?, ?, ?, ?> r -> Iso.of((Function) r.forward(), (Function) r.backward());
      case ForwardOnlyTransformTo<?, ?, ?, ?> r -> {
        // DEAD-BRANCH-DEFENSIVE: this throwingBackward lambda is unreachable via the public API.
        // Both factory entries Telescope.map(...) and Telescope.mapper(...) call
        // rejectForwardOnlyRows
        // up front; Telescope.mapperForward(...) accepts the row but never invokes the backward
        // leg. The guard remains so a future cross-package construction path that bypasses
        // rejectForwardOnlyRows produces a precise field-naming error rather than silent
        // corruption. NOT a coverage target.
        final String fieldName = r.sourceField();
        final Function<Object, Object> throwingBackward = y -> {
          throw new UnsupportedOperationException(
            "Mapping.toOneWay is forward-only — backward direction is undefined for field '" +
              fieldName +
              "'. Use Telescope.mapperForward(...) for a forward-only mapper, or" +
              " Mapping.to(src, tgt, forward, backward) for an explicit bidirectional" +
              " row."
          );
        };
        yield Iso.of((Function) r.forward(), throwingBackward);
      }
      case Via<?, ?> r -> liftViaIfNeeded(r, srcType, tgtType, sides, null);
      case ViaClass<?, ?> __ -> throw new IllegalStateException(
        "A via row naming a class reaches its field's iso only as the row resolvedRow makes of it"
      );
      case ViaMethods<?, ?> r -> liftViaIfNeeded(
        r.resolved(),
        srcType,
        tgtType,
        sides,
        LambdaIntrospection.conversionOf(r.forward())
      );
      // DEAD-BRANCH-DEFENSIVE block (cases below): every permit of the sealed Mapping hierarchy
      // that is NOT a per-field leaf Iso is filtered out by populateIso BEFORE this method is
      // called. The cases exist only because the sealed switch must stay exhaustive — if a future
      // permit is added to Mapping and populateIso forgets to short-circuit, the compiler forces a
      // case here whose throw surfaces the routing bug with a clear class name. NOT coverage
      // targets — these can only fire when a routing change in populateIso introduces a
      // regression, at which point the test failure is in populateIso, not here.
      case Drop<?, ?, ?> __ -> throw new IllegalStateException("Drop row should not reach fieldIsoOf");
      case TelescopeTo<?, ?, ?> __ -> throw new IllegalStateException("TelescopeTo row should not reach fieldIsoOf");
      case FromTelescopeTo<?, ?, ?> __ -> throw new IllegalStateException(
        "FromTelescopeTo row should not reach fieldIsoOf"
      );
      case TelescopeToTelescope<?, ?, ?> __ -> throw new IllegalStateException(
        "TelescopeToTelescope row should not reach fieldIsoOf"
      );
      case Constant<?, ?, ?> __ -> throw new IllegalStateException("Constant row should not reach fieldIsoOf");
      case Compute<?, ?, ?> __ -> throw new IllegalStateException("Compute row should not reach fieldIsoOf");
      case Conditional<?, ?> __ -> throw new IllegalStateException(
        "Conditional row should be unwrapped by populateIso before fieldIsoOf"
      );
    };
  }

  /**
   * Decide whether the {@link Via} row's nested mapper should be lifted through a container. When
   * the accessor's source/target field types are same-kind containers and the mapper's
   * source/target classes match the element classes, lift via {@link Iso#liftList} / {@link
   * Iso#liftSet} / {@link Iso#liftOptional} / {@link Iso#liftMapValues}. Otherwise the
   * element-level Iso flows through unchanged (scalar / record-pair case).
   */
  @SuppressWarnings("unchecked")
  private static Iso<?, ?> liftViaIfNeeded(
    final Via<?, ?> row,
    final Type srcType,
    final Type tgtType,
    final RowSides sides,
    final LambdaIntrospection.Conversion methods
  ) {
    // Inline: build the element-level Iso from the nested Mapper's public forward/backward, and
    // pull the source/target classes from the same Mapper. Reading the public components inline
    // keeps Iso / Class accessors off Via's public surface.
    final var raw = (Mapper<Object, Object>) row.nested();
    final var elementIso = Iso.of(raw::forward, raw::backward);
    final var mapperSrc = raw.sourceClass();
    final var mapperTgt = raw.targetClass();
    final var srcView = PAIRING.containerViewOf(srcType);
    final var tgtView = PAIRING.containerViewOf(tgtType);
    // A Collection-declared field names no shape of its own, so each view is settled against the
    // other before the two kinds are compared. Reading them unsettled pairs two COLLECTION views,
    // finds the kinds equal, and reaches a lift no allocator answers for.
    final var srcShape = PAIRING.settledAgainst(srcView, tgtView);
    final var tgtShape = PAIRING.settledAgainst(tgtView, srcView);
    if (methods != null) refuseNarrowedInput(sides, srcShape, tgtShape, srcType, methods);
    if (
      srcShape != null &&
      tgtShape != null &&
      srcShape.kind() == tgtShape.kind() &&
      elementTypeMatches(srcShape.elementType(), mapperSrc) &&
      (elementTypeMatches(tgtShape.elementType(), mapperTgt) ||
        (methods != null &&
          methods.resultInferred() &&
          tgtShape.elementType() instanceof Class<?> written &&
          mapperTgt.isAssignableFrom(written)))
    ) {
      if (srcShape.kind() == ContainerView.Kind.MAP_VALUES && !srcShape.keyType().equals(tgtShape.keyType())) {
        throw new IllegalStateException(
          "Deep map via(...): Map key types must match exactly — source " +
            ((Class<?>) srcShape.keyType()).getName() +
            " vs target " +
            ((Class<?>) tgtShape.keyType()).getName() +
            ". Key types must match exactly; auto-lifting preserves the source keys."
        );
      }
      return switch (srcShape.kind()) {
        case LIST -> ContainerLifts.liftListIntoTargetRaw(eraseIso(elementIso), srcType, tgtType);
        case SET -> ContainerLifts.liftSetIntoTargetRaw(eraseIso(elementIso), srcType, tgtType);
        case MAP_VALUES -> ContainerLifts.liftMapIntoTargetRaw(eraseIso(elementIso), srcType, tgtType);
        // Optional is final; no subclasses, no allocator needed.
        case OPTIONAL -> Iso.liftOptional(eraseIso(elementIso));
        // Settled against the other side of the pair above, so a view still carrying it here means
        // the settling stopped happening.
        case COLLECTION -> throw new IllegalStateException(
          "a Collection-declared container reached the lift without taking a shape"
        );
      };
    }
    refuseUnfit(sides, "source", sides.srcField(), srcType, mapperSrc);
    refuseUnfit(sides, "target", sides.tgtField(), tgtType, mapperTgt);
    return elementIso;
  }

  /**
   * Refuses a via row of two method references whose {@code forward} takes only {@code methods}'
   * class, the class {@code backward} returns, when that class admits neither the property's
   * elements nor the property itself: the lambda the compiler builds for {@code forward} casts each
   * value to it, so some value the property holds would not convert. A {@code forward} whose own
   * parameter admits neither is left to the refusal of a mapper that never fits.
   */
  private static void refuseNarrowedInput(
    final RowSides sides,
    final ContainerView<Type> srcShape,
    final ContainerView<Type> tgtShape,
    final Type srcType,
    final LambdaIntrospection.Conversion methods
  ) {
    final var takes = methods.from();
    final Class<?> element =
      srcShape != null &&
      tgtShape != null &&
      srcShape.kind() == tgtShape.kind() &&
      srcShape.elementType() instanceof Class<?> held
        ? boxed(held)
        : null;
    final var property = boxed(heldClassOf(srcType));
    if (element != null && takes.isAssignableFrom(element)) return;
    if (takes.isAssignableFrom(property)) return;
    final var forElement = element != null && methods.declared().isAssignableFrom(element);
    if (!forElement && !methods.declared().isAssignableFrom(property)) return;
    final var held = forElement ? element : property;
    throw new IllegalArgumentException(
      PairingMessages.viaMethodsNarrowed(
        sides.source().getSimpleName(),
        sides.target().getSimpleName(),
        sides.srcField(),
        takes.getSimpleName(),
        held.getSimpleName()
      )
    );
  }

  /**
   * {@code row}, or, for a via row naming a class, the via row of the methods the class has for a
   * property the source declares as {@code srcType} and the target as {@code tgtType}; see {@link
   * ViaClasses}. The class's methods are read here, while the runtime path builds a mapper, and
   * nowhere else.
   */
  private static Mapping<?, ?> resolvedRow(
    final Mapping<?, ?> row,
    final Type srcType,
    final Type tgtType,
    final RowSides sides
  ) {
    if (!(row instanceof ViaClass<?, ?> named)) return row;
    final var srcView = PAIRING.containerViewOf(srcType);
    final var tgtView = PAIRING.containerViewOf(tgtType);
    final var srcShape = PAIRING.settledAgainst(srcView, tgtView);
    final var tgtShape = PAIRING.settledAgainst(tgtView, srcView);
    final var lifts = srcShape != null && tgtShape != null && srcShape.kind() == tgtShape.kind();
    return ViaClasses.resolve(
      named,
      srcType,
      tgtType,
      lifts ? srcShape.elementType() : null,
      lifts ? tgtShape.elementType() : null,
      sides.source().getSimpleName(),
      sides.target().getSimpleName(),
      sides.srcField(),
      sides.tgtField()
    );
  }

  /** The pair a row converts at and the two properties it reads and writes, named for a refusal. */
  private record RowSides(Class<?> source, Class<?> target, String srcField, String tgtField) {}

  /**
   * Refuses a {@code via} row whose mapper is handed {@code property} as it is when no value of
   * that property can be an instance of {@code mapperClass}: every call would throw a {@code
   * ClassCastException}. A primitive on either side counts as its wrapper.
   */
  private static void refuseUnfit(
    final RowSides sides,
    final String side,
    final String property,
    final Type propertyType,
    final Class<?> mapperClass
  ) {
    final var held = heldClassOf(propertyType);
    if (mayBeInstance(boxed(held), boxed(mapperClass))) return;
    throw new IllegalArgumentException(
      PairingMessages.viaMapperNeverFits(
        sides.source().getSimpleName(),
        sides.target().getSimpleName(),
        side,
        property,
        mapperClass.getSimpleName()
      )
    );
  }

  /** {@code type}'s wrapper class when it is a primitive, and {@code type} otherwise. */
  private static Class<?> boxed(final Class<?> type) {
    return MethodType.methodType(type).wrap().returnType();
  }

  /**
   * The class every value of {@code type}, a property's type, is an instance of: a type variable's
   * bound, followed through any variable that bound names, and an array of the class its component
   * is held as.
   */
  static Class<?> heldClassOf(final Type type) {
    if (type instanceof TypeVariable<?> variable) return boundOf(variable);
    if (type instanceof GenericArrayType array) return heldClassOf(array.getGenericComponentType()).arrayType();
    return rawClassOf(type);
  }

  /**
   * Whether a container's {@code elementType} is what the element mapper converts: the mapper's own
   * class, or a type variable whose bound admits it, since such a container may hold the mapper's
   * class.
   */
  private static boolean elementTypeMatches(final Type elementType, final Class<?> mapperClass) {
    if (elementType instanceof Class<?> cls) return cls.equals(mapperClass);
    return elementType instanceof TypeVariable<?> variable && boundOf(variable).isAssignableFrom(mapperClass);
  }

  /** The class a type variable's first bound erases to, following a bound that is a variable. */
  static Class<?> boundOf(final TypeVariable<?> variable) {
    final var bound = variable.getBounds()[0];
    return bound instanceof TypeVariable<?> next ? boundOf(next) : rawClassOf(bound);
  }

  /**
   * Pick the right {@link Reflective} for {@code cls}: records always go through {@link
   * Reflective#RECORDS}; beans go through {@code beanRefl}, which is built once per {@link
   * #resolution} call (a singleton {@link Reflective#BEANS} when no hints exist, otherwise a single
   * hint-aware instance shared across the entire recursion).
   */
  private static Reflective pickReflective(final Class<?> cls, final Reflective beanRefl) {
    return cls.isRecord() ? Reflective.RECORDS : beanRefl;
  }

  /**
   * "field" for records, "property" for beans — used in error messages to read correctly per side.
   */
  private static String slot(final Reflective refl) {
    return refl == Reflective.RECORDS ? "field" : "property";
  }

  /**
   * Constant to constant by name, through a table indexed by the converted constant's ordinal,
   * built once when the mapper is. A constant with no same-named counterpart has a null entry,
   * which only a forward-only mapper's unused backward table can hold, since every other such pair
   * is refused.
   */
  private static Iso<Object, Object> enumByNameIso(final Class<?> src, final Class<?> tgt) {
    final var forward = constantsByName(src, tgt);
    final var backward = constantsByName(tgt, src);
    return Iso.of(
      s -> s == null ? null : forward[((Enum<?>) s).ordinal()],
      t -> t == null ? null : backward[((Enum<?>) t).ordinal()]
    );
  }

  /** For each constant of {@code from}, by ordinal, the constant of {@code to} with its name. */
  private static Object[] constantsByName(final Class<?> from, final Class<?> to) {
    final var toConstants = to.getEnumConstants();
    final var byName = HashMap.<String, Object>newHashMap(toConstants.length);
    for (final var constant : toConstants) byName.put(((Enum<?>) constant).name(), constant);
    final var fromConstants = from.getEnumConstants();
    final var table = new Object[fromConstants.length];
    for (var i = 0; i < fromConstants.length; i++) table[i] = byName.get(((Enum<?>) fromConstants[i]).name());
    return table;
  }

  /**
   * Build the per-field Iso for a primitive ↔ wrapper pair. The Iso's {@code to} (forward)
   * substitutes the primitive default when the source value is null, so a wrapper-to-primitive
   * write never NPEs at the setter. {@code from} (backward) is symmetric.
   */
  private static Iso<Object, Object> primitiveWrapperIso(final Class<?> src, final Class<?> tgt) {
    final var fwdDefault = tgt.isPrimitive() ? Placeholders.primitiveDefault(tgt) : null;
    final var bwdDefault = src.isPrimitive() ? Placeholders.primitiveDefault(src) : null;
    return Iso.of(v -> v == null ? fwdDefault : v, v -> v == null ? bwdDefault : v);
  }

  // ---------- Iso assembly (lattice-routed) ----------

  /**
   * Build the per-pair {@code Iso<S, T>} as a three-step lattice composition:
   *
   * <ol>
   *   <li>{@code srcReader = srcRefl.structuralIso(source).reverse()} — peels S into a {@code
   *       Map<String, Object>} keyed by source field names.
   *   <li>{@code remap} — transforms that source-keyed map into a target-keyed map by walking the
   *       step table: rename keys ({@code sourceName} → {@code targetName}) and apply each
   *       per-field {@code Iso} forward/backward.
   *   <li>{@code tgtBuilder = tgtRefl.structuralIso(target)} — builds T from the target-keyed map.
   * </ol>
   *
   * <p>The composed {@code Iso<S, T>} is {@code srcReader.then(remap).then(tgtBuilder)} — pure
   * {@code .then(...)} all the way down. No inline forward/backward lambda bodies; the
   * structural-decomposition primitive lives on {@link Reflective#structuralIso} and the per-field
   * {@link Iso}s are already lattice values.
   */
  private static <S, T> Iso<S, T> assembleIso(
    final Class<S> source,
    final Class<T> target,
    final Reflective srcRefl,
    final Reflective tgtRefl,
    final Map<String, FieldStep> byTargetName,
    final Map<String, FieldStep> bySourceName
  ) {
    // Pre-resolve the holder data on the :core side (Telescope is visible here) and pass into
    // Reflective.structuralIso so :internal stays compile-time-oblivious to :core — no callback,
    // no global state, no static-init bridge. holderReadersFor returns null when the holder is
    // missing OR doesn't cover every component; the Iso then falls back to the reflective read
    // path (matches the prior dispatch-shape invariant: one branch outside the Iso, not N
    // branches inside).
    final var srcNames = srcRefl.names(source);
    final var tgtNames = tgtRefl.names(target);
    final var slotMaps = buildSlotMaps(byTargetName, bySourceName, srcNames, tgtNames);
    final Iso<Object, Object> identity = Iso.identity();

    // Composed-handle leaf for record/bean pairs: a direction is one (S)→T or (T)→S MethodHandle —
    // no Object[] intermediate, no boxing on same-type fields (identity slots read
    // primitive-to-primitive straight into the canonical constructor, a bean's constructor, its
    // builder members or its setters). Non-identity slots (rename with conversion, nested pair,
    // container lift) still route through their per-slot Iso. This is a build-time shape decision,
    // not a runtime fallback, and it stays lattice-routed — the composed handles are the leaf Iso's
    // transforms.
    //
    // Each direction is decided on its own, by the side it builds: it composes when
    // MhIso.composesBuild reproduces the writer that side's path picks — the write hint, the
    // default
    // strategy or the auto order — so the composed build and the array leaf build the same value.
    // The side a direction only reads asks nothing, so a forward-only mapper from a source no
    // composed build reproduces still composes its forward direction.
    final var targetWriter = tgtRefl.writerFor(target);
    final var sourceWriter = srcRefl.writerFor(source);
    final var composeForward = MhIso.composesBuild(source, target, targetWriter);
    final var composeBackward = MhIso.composesBuild(target, source, sourceWriter);
    final Iso<S, T> arrayLeaf =
      composeForward && composeBackward
        ? null
        : arrayLeaf(
            source,
            target,
            srcRefl,
            tgtRefl,
            srcNames,
            tgtNames,
            slotMaps,
            identity,
            targetWriter,
            sourceWriter
          );
    if (!composeForward && !composeBackward) return arrayLeaf;
    @SuppressWarnings("unchecked")
    final var other = (Iso<Object, Object>) (Iso<?, ?>) arrayLeaf;
    return MhIso.pair(
      source,
      target,
      slotMaps.fwdSrcPos(),
      slotMaps.fwdIso(),
      slotMaps.bwdTgtPos(),
      slotMaps.bwdIso(),
      identity,
      other,
      composeForward,
      composeBackward,
      targetWriter,
      sourceWriter
    );
  }

  /**
   * The array leaf for a direction the composed handles do not build — a bean side written through
   * a builder or an all-args constructor, or one a write hint sends to a writer other than its
   * setters.
   */
  private static <S, T> Iso<S, T> arrayLeaf(
    final Class<S> source,
    final Class<T> target,
    final Reflective srcRefl,
    final Reflective tgtRefl,
    final String[] srcNames,
    final String[] tgtNames,
    final SlotMaps slotMaps,
    final Iso<Object, Object> identity,
    final Beans.BeanWriter<?> targetWriter,
    final Beans.BeanWriter<?> sourceWriter
  ) {
    // A slot whose property the writer does not write — a builder with no member for it, a setter
    // writer with no setter — is neither read nor converted, as the composed build leaves it out.
    final var fwdTakes = takenSlots(tgtNames, targetWriter);
    final var bwdTakes = takenSlots(srcNames, sourceWriter);
    // Fused-source-and-remap: bypass the source-side Object[] intermediate. The previous shape
    // ran S → Object[srcArity] (srcReader) → Object[tgtArity] (remap) → T (tgtBuilder) — two
    // intermediate arrays + three Iso.then virtual dispatches per call. The fused body inlines
    // all three stages: target Object[tgtArity] alloc, then for each target slot pull the source
    // value directly from srcReaders[fwdSrcPos[i]].apply(s) and apply the per-slot Iso. Saves one
    // alloc + 5 array writes + 5 array reads + 2 virtual Iso.then hops per call. Identity-Iso
    // short-circuit preserved against the cached singleton from Iso.identity().
    final var srcHolderReaders = Telescope.holderReadersFor(source, srcNames);
    final var srcHolderConstructor = Telescope.holderConstructorFor(source);
    final var tgtHolderReaders = Telescope.holderReadersFor(target, tgtNames);
    final var tgtHolderConstructor = Telescope.holderConstructorFor(target);
    final var srcReaders = srcRefl.positionalReaders(source, srcHolderReaders);
    final var tgtReaders = tgtRefl.positionalReaders(target, tgtHolderReaders);
    final var tgtBuilderFn = tgtRefl.positionalBuilder(target, tgtHolderReaders, tgtHolderConstructor);
    final var srcBuilderFn = srcRefl.positionalBuilder(source, srcHolderReaders, srcHolderConstructor);
    return Iso.of(
      s -> {
        if (s == null) return null;
        final var tgtArr = new Object[slotMaps.tgtArity()];
        for (var i = 0; i < slotMaps.tgtArity(); i++) {
          if (!fwdTakes[i]) continue;
          final var sp = slotMaps.fwdSrcPos()[i];
          final var v = sp < 0 ? null : srcReaders[sp].apply(s);
          final var iso = slotMaps.fwdIso()[i];
          tgtArr[i] = iso == identity ? v : iso.to(v);
        }
        return tgtBuilderFn.apply(tgtArr);
      },
      t -> {
        if (t == null) return null;
        final var srcArr = new Object[slotMaps.srcArity()];
        for (var i = 0; i < slotMaps.srcArity(); i++) {
          if (!bwdTakes[i]) continue;
          final var tp = slotMaps.bwdTgtPos()[i];
          final var v = tp < 0 ? null : tgtReaders[tp].apply(t);
          final var iso = slotMaps.bwdIso()[i];
          srcArr[i] = iso == identity ? v : iso.from(v);
        }
        return srcBuilderFn.apply(srcArr);
      }
    );
  }

  /**
   * Per slot, whether {@code writer} writes that property; every slot for a record's null writer.
   */
  private static boolean[] takenSlots(final String[] names, final Beans.BeanWriter<?> writer) {
    final var taken = new boolean[names.length];
    for (var i = 0; i < names.length; i++) taken[i] = writer == null || writer.writes(names[i]);
    return taken;
  }

  /**
   * Precomputes the source→target and target→source slot translation tables + per-slot Iso arrays
   * once at type-pair build time. Sentinel slot {@code -1} for "no corresponding source/target
   * field" (placeholder rows where {@code step.sourceName} or {@code step.targetName} is null) —
   * the value defaults to {@code null}, matching the prior {@code Map.get(missingKey)} semantics.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static SlotMaps buildSlotMaps(
    final Map<String, FieldStep> byTargetName,
    final Map<String, FieldStep> bySourceName,
    final String[] srcNames,
    final String[] tgtNames
  ) {
    final var srcIndex = indexMap(srcNames);
    final var tgtIndex = indexMap(tgtNames);
    final var srcArity = srcNames.length;
    final var tgtArity = tgtNames.length;
    final var fwdSrcPos = new int[tgtArity];
    final var fwdIso = (Iso<Object, Object>[]) new Iso[tgtArity];
    final Iso<Object, Object> identity = Iso.identity();
    for (var i = 0; i < tgtArity; i++) {
      final var step = byTargetName.get(tgtNames[i]);
      if (step == null) {
        fwdSrcPos[i] = -1;
        fwdIso[i] = identity;
      } else {
        final var srcPos = step.sourceName == null ? null : srcIndex.get(step.sourceName);
        fwdSrcPos[i] = srcPos == null ? -1 : srcPos;
        fwdIso[i] = (Iso<Object, Object>) step.iso;
      }
    }
    final var bwdTgtPos = new int[srcArity];
    final var bwdIso = (Iso<Object, Object>[]) new Iso[srcArity];
    for (var i = 0; i < srcArity; i++) {
      final var step = bySourceName.get(srcNames[i]);
      if (step == null) {
        bwdTgtPos[i] = -1;
        bwdIso[i] = identity;
      } else {
        final var tgtPos = step.targetName == null ? null : tgtIndex.get(step.targetName);
        bwdTgtPos[i] = tgtPos == null ? -1 : tgtPos;
        bwdIso[i] = (Iso<Object, Object>) step.iso;
      }
    }
    return new SlotMaps(srcArity, tgtArity, fwdSrcPos, fwdIso, bwdTgtPos, bwdIso);
  }

  private record SlotMaps(
    int srcArity,
    int tgtArity,
    int[] fwdSrcPos,
    Iso<Object, Object>[] fwdIso,
    int[] bwdTgtPos,
    Iso<Object, Object>[] bwdIso
  ) {}

  private static Map<String, Integer> indexMap(final String[] names) {
    final var m = new HashMap<String, Integer>(names.length * 2);
    for (var i = 0; i < names.length; i++) m.put(names[i], i);
    return m;
  }

  // ---------- Cycle-safe cache reader ----------

  /**
   * The path of the public mapping invocation running on this thread, one per direction, by the
   * pair of classes each object is converted between, as {@link ActivePath} keys them. A generated
   * bridge that {@linkplain BridgeFn#takesActivePath() takes the active path} keys it the same way,
   * so a path handed to such a bridge runs on through it. A bridge is handed the path only by the
   * walk that opened it, so a mapper called from inside another one, through a row's function or a
   * transform, starts a path of its own.
   */
  private static final ThreadLocal<ActivePath> FORWARD_SEEN = new ThreadLocal<>();

  private static final ThreadLocal<ActivePath> BACKWARD_SEEN = new ThreadLocal<>();

  /** {@code owner} is the walk the pair belongs to. */
  @SuppressWarnings("unchecked")
  private static Iso<?, ?> lazyCacheIso(
    final Map<TypePair, Iso<?, ?>> cache,
    final TypePair key,
    final boolean acyclic,
    final Object owner
  ) {
    // Acyclic-bypass: when the static type graph has no path from this pair back to itself, the
    // value-level cycle guard cannot fire. Skip the ThreadLocal probe + IdentityHashMap insert
    // entirely — ~15 ns saved per nested hop. Verified at populateIso time via the cyclicPairs
    // tracker; if a downstream change introduces a back-edge that the analysis can't see (e.g. an
    // Object-typed component holding an instance-level cycle), the bypass would be unsafe — but the
    // structural typing already prevents DeepMap from descending into Object-typed fields.
    if (acyclic) {
      // populateIso(key) has completed by now (the RecursePair branch calls it before this), so the
      // cache entry is the finished sub-Iso, not the in-progress null reservation. When it is a
      // composed-handle leaf, hand it to the parent directly: the leaf null-guards internally, so
      // this is byte-identical to the null-guarding proxy below, and MhIso.pair can then inline the
      // leaf's raw handle (full-tree fusion). Every other sub-Iso keeps the null-guarding proxy.
      final var cached = cache.get(key);
      if (MhIso.isComposedLeaf(cached)) return cached;
      return Iso.of(
        v -> v == null ? null : ((Iso<Object, Object>) cached).to(v),
        v -> v == null ? null : ((Iso<Object, Object>) cached).from(v)
      );
    }
    return Iso.of(
      v -> cycleSafe(FORWARD_SEEN, owner, key.source, key.target, v, x -> ((Iso<Object, Object>) cache.get(key)).to(x)),
      v ->
        cycleSafe(BACKWARD_SEEN, owner, key.target, key.source, v, x -> ((Iso<Object, Object>) cache.get(key)).from(x))
    );
  }

  /**
   * Converts {@code value} from {@code from} into {@code into} on a path of its own, for the walk
   * {@code owner}. Reentrant public calls get independent paths; the caller's is restored even
   * after an exception.
   */
  private static <X, Y> Y inInvocation(
    final ThreadLocal<ActivePath> ref,
    final Object owner,
    final Class<?> from,
    final Class<?> into,
    final X value,
    final Function<X, Y> body
  ) {
    if (value == null) return null;
    final var previous = ref.get();
    ref.set(new ActivePath(owner));
    try {
      return cycleSafe(ref, owner, from, into, value, body);
    } finally {
      if (previous == null) ref.remove();
      else ref.set(previous);
    }
  }

  /**
   * Only an identity already on the active path is a cycle; siblings map independently. A pair met
   * with no path open, as a patch entry meets it, starts one for the walk {@code owner}.
   */
  private static <X, Y> Y cycleSafe(
    final ThreadLocal<ActivePath> ref,
    final Object owner,
    final Class<?> from,
    final Class<?> into,
    final X value,
    final Function<X, Y> body
  ) {
    if (value == null) return null;
    final var path = ref.get();
    if (path == null) return inInvocation(ref, owner, from, into, value, body);
    final var active = path.objects(from, into);
    if (active.putIfAbsent(value, Boolean.TRUE) != null) return null;
    try {
      return body.apply(value);
    } finally {
      active.remove(value);
    }
  }

  @SuppressWarnings("unchecked")
  private static <X, Y> Iso<X, Y> eraseIso(final Iso<?, ?> iso) {
    return (Iso<X, Y>) iso;
  }

  // ---------- Inner types ----------

  private record TypePair(Class<?> source, Class<?> target) implements Paired<Type> {}

  /**
   * The {@code cases} rows of one mapper, and how each pair of sealed interfaces the mapper meets
   * pairs its cases.
   *
   * <p>A case of the source pairs with the case of the target a row names for it, and otherwise
   * with the case of the target its own {@code @Bridge} converts to, found by {@link #ownBridge}:
   * the pairing a {@code @Bridge} on the sealed source generates. A case neither pairs is refused,
   * and on a mapper that converts backward so is a case of the target that no case or several cases
   * pair with, since the backward direction has to build it from exactly one.
   */
  private static final class SealedCases {

    private final List<Cases> rows;
    private final boolean[] taken;
    private final Map<TypePair, List<TypePair>> paired = new HashMap<>();
    private final Set<TypePair> throughBridge = new HashSet<>();

    SealedCases(final List<Cases> rows) {
      this.rows = List.copyOf(rows);
      this.taken = new boolean[rows.size()];
    }

    /**
     * The case pairs of {@code roots}, one per case of the source, in the order it permits them.
     */
    List<TypePair> pairsOf(final TypePair roots, final boolean forwardOnly) {
      final var known = paired.get(roots);
      if (known != null) return known;
      final var source = roots.source.getSimpleName();
      final var target = roots.target.getSimpleName();
      final var sourceCases = List.of(roots.source.getPermittedSubclasses());
      final var targetCases = List.of(roots.target.getPermittedSubclasses());
      final var named = new HashMap<Class<?>, Class<?>>();
      for (var i = 0; i < rows.size(); i++) {
        final var row = rows.get(i);
        if (!sourceCases.contains(row.sourceCase()) || !targetCases.contains(row.targetCase())) continue;
        taken[i] = true;
        if (named.putIfAbsent(row.sourceCase(), row.targetCase()) != null) throw new IllegalArgumentException(
          PairingMessages.caseRowPairedTwice(source, target, row.sourceCase().getSimpleName())
        );
      }
      final var pairs = new ArrayList<TypePair>();
      for (final var sourceCase : sourceCases) {
        var targetCase = named.get(sourceCase);
        if (targetCase == null) {
          final var bridged = new ArrayList<Class<?>>();
          for (final var candidate : targetCases) {
            if (ownBridge(sourceCase, candidate, false).isPresent()) bridged.add(candidate);
          }
          if (bridged.isEmpty()) {
            // A holder the lookup above passed over is a long-form one whose bridge does not
            // declare its pair, which the runtime does not pair cases through.
            for (final var candidate : targetCases) {
              final var undeclared = BridgeHolderProbe.longFormFor(sourceCase, candidate);
              if (undeclared.isPresent()) throw new IllegalStateException(
                PairingMessages.undeclaredCaseBridge(
                  source,
                  target,
                  sourceCase.getSimpleName(),
                  candidate.getSimpleName(),
                  undeclared.get().bridgeClass().getSimpleName()
                )
              );
            }
            throw new IllegalStateException(PairingMessages.unpairedCase(source, target, sourceCase.getSimpleName()));
          }
          if (bridged.size() > 1) throw new IllegalStateException(
            PairingMessages.ambiguousCase(
              source,
              target,
              sourceCase.getSimpleName(),
              bridged.stream().map(Class::getSimpleName).toList()
            )
          );
          targetCase = bridged.getFirst();
          throughBridge.add(new TypePair(sourceCase, targetCase));
        }
        final var crossed = PAIRING.caseArgumentsRefusal(roots.source, roots.target, sourceCase, targetCase);
        if (crossed != null) throw new IllegalStateException(crossed);
        pairs.add(new TypePair(sourceCase, targetCase));
      }
      if (!forwardOnly) for (final var targetCase : targetCases) {
        final var from = pairs
          .stream()
          .filter(pair -> pair.target == targetCase)
          .map(pair -> pair.source.getSimpleName())
          .toList();
        if (from.isEmpty()) throw new IllegalStateException(
          PairingMessages.targetCaseUnpaired(source, target, targetCase.getSimpleName())
        );
        if (from.size() > 1) throw new IllegalStateException(
          PairingMessages.targetCasePairedTwice(source, target, targetCase.getSimpleName(), from)
        );
      }
      final var decided = List.copyOf(pairs);
      paired.put(roots, decided);
      return decided;
    }

    /** Whether {@code pair} was paired through the source case's own bridge, not by a row. */
    boolean pairedThroughBridge(final TypePair pair) {
      return throughBridge.contains(pair);
    }

    /** The case pairs already decided for {@code roots}, or null when the mapper never met it. */
    List<TypePair> decided(final TypePair roots) {
      return paired.get(roots);
    }

    /** Refuse the rows no sealed pair the mapper dispatched on took. */
    void refuseUntaken(final Class<?> topSource, final Class<?> topTarget) {
      final var untaken = new ArrayList<String>();
      for (var i = 0; i < rows.size(); i++) {
        if (taken[i]) continue;
        final var row = rows.get(i);
        untaken.add(PairingMessages.caseRow(row.sourceCase().getSimpleName(), row.targetCase().getSimpleName()));
      }
      if (!untaken.isEmpty()) throw new IllegalArgumentException(
        PairingMessages.caseRowsNeverDispatched(topSource.getSimpleName(), topTarget.getSimpleName(), untaken)
      );
    }
  }

  /**
   * The generated bridge each nested pair converts through, for one walk.
   *
   * <p>A nested pair converts through the bridge its own {@code @Bridge} generated, found by {@link
   * #ownBridge}, so that bridge's transforms, leniency, renames and write strategy decide the
   * pair's fields on this path as they do on the generated one. The walk converts the pair itself
   * instead when the bridge would pass over something the mapper says:
   *
   * <ul>
   *   <li>a row keyed to the pair, or to a pair its classes reach, or a {@code cases} row pairing
   *       two cases its classes reach;
   *   <li>a write hint naming one of its classes, or a class they reach;
   *   <li>a type the pair reaches that is an ancestor on the walk's path, when the bridge does not
   *       {@linkplain BridgeFn#takesActivePath() take the active path}, because the walk's guard
   *       against an object reaching itself would not pass through it.
   * </ul>
   *
   * <p>A bridge that takes the active path converts a pair on a cycle along the walk's own path, so
   * the reference that closes the cycle converts to null where it does on the generated path.
   *
   * <p>A mapper-wide {@code nullSourceValues(DEFAULT)} or {@code writeBeans(...)} default reaches
   * every pair, and a bridge has no counterpart for either, so such a mapper walks every pair.
   */
  private static final class OwnBridges {

    // The shared decision, given what the mapper is given.
    private final OwnBridgeRule<Type> rule;
    private final Set<TypePair> converted = new HashSet<>();
    // The walk's pairs whose objects are kept on the active path while they convert.
    private final Set<TypePair> cyclicPairs;

    OwnBridges(
      final Map<TypePair, List<Mapping<?, ?>>> rows,
      final List<Cases> caseRows,
      final Set<Class<?>> hinted,
      final String everyPair,
      final Set<TypePair> cyclicPairs
    ) {
      final List<Paired<Type>> cases = caseRows.isEmpty() ? List.of() : new ArrayList<>(caseRows.size());
      for (final var row : caseRows) cases.add(new PairWalk.Pair<>(row.sourceCase(), row.targetCase()));
      this.rule = new OwnBridgeRule<>(PROPS, PAIRING, PAIR_WALK, everyPair, rows.keySet(), cases, List.copyOf(hinted));
      this.cyclicPairs = cyclicPairs;
    }

    /**
     * The pair's own bridge as an {@link Iso}, or null when the walk converts it. {@code ancestors}
     * are the pairs being converted around it.
     */
    Iso<?, ?> isoFor(final TypePair pair, final Collection<TypePair> ancestors) {
      final var bridge = bridgeFor(pair, ancestors);
      return bridge == null ? null : isoOf(pair, bridge, this);
    }

    /** As {@link #isoFor}, the generated bridge itself. */
    Object bridgeFor(final TypePair pair, final Collection<TypePair> ancestors) {
      final var bridge = standingIn(pair, ancestors);
      if (bridge == null) return null;
      markCycle(pair, ancestors, bridge);
      converted.add(pair);
      return bridge;
    }

    /**
     * Whether the pair's own bridge converts it, as {@link #bridgeFor} decides, deciding nothing.
     */
    boolean standsIn(final TypePair pair, final Collection<TypePair> ancestors) {
      return standingIn(pair, ancestors) != null;
    }

    private Object standingIn(final TypePair pair, final Collection<TypePair> ancestors) {
      if (rule.settingReason(pair.source, pair.target, ancestors) != null) return null;
      final var found = ownBridge(pair.source, pair.target, false);
      if (found.isEmpty() || rule.reachReason(pair.source, pair.target, ancestors, factsOf(found.get())) != null) {
        return null;
      }
      return found.get();
    }

    /** What the generated bridge {@code bridge} says of itself, as the shared rule reads it. */
    private static OwnBridgeRule.Bridge factsOf(final Object bridge) {
      final var fn = Telescope.bridgeFnOf(bridge);
      final var listed = fn != null && fn.declaresUnwalkedProperties();
      return new OwnBridgeRule.Bridge(
        listed,
        listed ? fn.unwalkedSourceProperties() : Set.of(),
        listed ? fn.unwalkedTargetProperties() : Set.of(),
        fn != null && fn.takesActivePath()
      );
    }

    /**
     * Marks as cyclic the pairs around {@code pair} that lie on a cycle running through its bridge:
     * the outermost ancestor the bridge reaches, and every ancestor inside it. Their objects then
     * stay on the active path while they convert, which is what the bridge reads to cut the cycle.
     * {@code ancestors} runs from the innermost pair out.
     */
    private void markCycle(final TypePair pair, final Collection<TypePair> ancestors, final Object bridge) {
      final var fn = Telescope.bridgeFnOf(bridge);
      if (fn == null || !fn.takesActivePath()) return;
      final var fromSource = rule.reachedFrom(
        pair.source,
        fn.unwalkedSourceProperties(),
        fn.declaresUnwalkedProperties()
      );
      final var fromTarget = rule.reachedFrom(
        pair.target,
        fn.unwalkedTargetProperties(),
        fn.declaresUnwalkedProperties()
      );
      final var around = List.copyOf(ancestors);
      for (var i = around.size() - 1; i >= 0; i--) {
        final var ancestor = around.get(i);
        if (fromSource.contains(ancestor.source.getName()) && fromTarget.contains(ancestor.target.getName())) {
          cyclicPairs.addAll(around.subList(0, i + 1));
          return;
        }
      }
    }

    /**
     * Why the walk converts {@code pair} although it has a bridge of its own, or null when that
     * bridge converts it.
     */
    String passedOver(final TypePair pair, final Collection<TypePair> ancestors) {
      final var setting = rule.settingReason(pair.source, pair.target, ancestors);
      if (setting != null) return setting;
      final var found = ownBridge(pair.source, pair.target, false);
      return found.isEmpty() ? null : rule.reachReason(pair.source, pair.target, ancestors, factsOf(found.get()));
    }

    /**
     * Whether the pair has a bridge of its own that nothing the mapper is given about the pair
     * keeps from converting it; the pairs around it may still.
     */
    boolean mayStandIn(final TypePair pair) {
      return (
        rule.configuredReason(pair.source, pair.target) == null &&
        ownBridge(pair.source, pair.target, false).isPresent()
      );
    }

    boolean converts(final TypePair pair) {
      return converted.contains(pair);
    }
  }

  /** Each pair's bridge, or its absence, by source class. A malformed bridge is not kept. */
  private static final ClassValue<ConcurrentHashMap<Class<?>, Optional<Object>>> NESTED_BRIDGES = cacheBySource();

  /** The same, for a mapper's own pair, where a long-form holder is read too. */
  private static final ClassValue<ConcurrentHashMap<Class<?>, Optional<Object>>> ROOT_BRIDGES = cacheBySource();

  private static ClassValue<ConcurrentHashMap<Class<?>, Optional<Object>>> cacheBySource() {
    return new ClassValue<>() {
      @Override
      protected ConcurrentHashMap<Class<?>, Optional<Object>> computeValue(final Class<?> type) {
        return new ConcurrentHashMap<>();
      }
    };
  }

  /**
   * The bridge {@code @Bridge} generated for {@code (source, target)}, found by the one lookup a
   * mapper with no steps and a nested pair share: a holder beside the source, then a carrier
   * registered as a {@link BridgeProvider}.
   *
   * <p>Beside the source, the {@code <Source>To<Target>Bridge} holder comes first, then {@code
   * <Source>Bridge}. The long name goes both to the bridges of a source declaring several targets
   * and to a bridge the processor derived for a pair with no {@code @Bridge} of its own, which
   * carries the configuration of whichever bridges reached that pair rather than the pair's own.
   * With {@code longForm}, for the pair a mapper with no steps is built for, either is read.
   * Without it, for a nested pair or a case of a sealed interface, a long-form holder is read only
   * when its bridge {@linkplain BridgeFn#declaresPair() declares the pair}, and a class under the
   * long name with no {@code BRIDGE} field reads as absent, as one under the short name does.
   */
  static Optional<Object> ownBridge(final Class<?> source, final Class<?> target, final boolean longForm) {
    final var known = (longForm ? ROOT_BRIDGES : NESTED_BRIDGES).get(source);
    final var cached = known.get(target);
    if (cached != null) return cached;
    // Read outside the table's update: reading a bridge initialises its class, and that can build a
    // mapper which looks a bridge up in this table.
    final var holder = longForm
      ? BridgeHolderProbe.probeFor(source, target)
      : BridgeHolderProbe.longFormFor(source, target)
          .filter(DeepMap::declaresPair)
          .or(() -> BridgeHolderProbe.shortFormFor(source, target));
    final Optional<Object> found = holder
      .map(BridgeHolderProbe.BridgeRef::bridge)
      .or(() -> BridgeRegistry.find(source, target, source.getClassLoader()));
    final var raced = known.putIfAbsent(target, found);
    return raced == null ? found : raced;
  }

  /** Whether the bridge {@code holder} holds declares its pair. */
  private static boolean declaresPair(final BridgeHolderProbe.BridgeRef holder) {
    final var fn = Telescope.bridgeFnOf(holder.bridge());
    return fn != null && fn.declaresPair();
  }

  /**
   * A nested pair's own bridge as the pair's leaf: a generated bridge converts null to null in both
   * directions. A bridge that {@linkplain BridgeFn#takesActivePath() takes the active path} is
   * handed the path of the walk {@code owner} when that walk has one open, so an object the walk is
   * converting is one the bridge finds on it; met outside that walk, the bridge starts its own.
   *
   * @throws IllegalStateException when the bridge is not a two-way conversion
   */
  @SuppressWarnings("unchecked")
  private static Iso<?, ?> isoOf(final TypePair pair, final Object bridge, final Object owner) {
    if (Telescope.bridgeFnOf(bridge) instanceof BridgeFn<?, ?> raw && raw.takesActivePath()) {
      final var fn = (BridgeFn<Object, Object>) raw;
      return Iso.of(
        v -> {
          final var path = FORWARD_SEEN.get();
          return path == null || path.owner() != owner ? fn.forward(v) : fn.forward(v, path);
        },
        v -> {
          final var path = BACKWARD_SEEN.get();
          return path == null || path.owner() != owner ? fn.backward(v) : fn.backward(v, path);
        }
      );
    }
    if (bridge instanceof Telescope<?, ?> telescope && telescope.optic instanceof Iso<?, ?> iso) return iso;
    throw new IllegalStateException(
      "The bridge generated for (" +
        pair.source.getName() +
        ", " +
        pair.target.getName() +
        ") is not a two-way conversion, so a mapper reaching that pair cannot convert it through" +
        " the bridge. Re-run the @Bridge processor."
    );
  }

  /**
   * One per-component step: source/target field names + the {@link Iso} between their leaf types.
   */
  private record FieldStep(
    String sourceName,
    String targetName,
    Iso<?, ?> iso,
    BiFunction<Object, Object, Object> overlay
  ) {
    FieldStep(final String sourceName, final String targetName, final Iso<?, ?> iso) {
      this(sourceName, targetName, iso, null);
    }
  }

  /**
   * Refuses a bean side whose setter cannot take a value of the property's own type, for every
   * property of {@code cls} a step fills from the other side: the target's properties when {@code
   * target} is true, and otherwise the source's, which the backward direction writes.
   */
  private static void refuseUnwritableSetters(
    final Class<?> cls,
    final Beans.BeanWriter<?> writer,
    final Map<String, FieldStep> steps,
    final boolean target
  ) {
    if (writer == null) return;
    for (final var step : steps.values()) {
      final var written = target ? step.targetName() : step.sourceName();
      final var from = target ? step.sourceName() : step.targetName();
      if (written == null || from == null) continue;
      final var refusal = Beans.setterRefusal(writer, cls, written);
      if (refusal != null) throw new IllegalStateException(refusal);
    }
  }

  /**
   * Strip generics from a {@link Type} to the raw {@link Class}, returning {@code null} for
   * anything that isn't a class or a parameterized type (wildcards, type variables, etc.).
   */
  private static Class<?> rawClassOf(final Type t) {
    if (t instanceof Class<?> c) return c;
    if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) return c;
    return null;
  }

  /**
   * Bundled return from {@link #resolution(Class, Class, MapStep[], boolean)} — the Iso, the patch
   * table, for a pair of two sealed interfaces each source case's mapper ({@code cases} is null for
   * any other pair), and the reflective that rebuilds the source, which carries the call's write
   * hints so a patch builds a bean the way backward does.
   */
  private record Resolution<A, B>(
    Iso<A, B> iso,
    Map<String, Mapper.PatchEntry> patchTable,
    List<Mapper.CaseEntry> cases,
    List<OpticNode> trail,
    Reflective sourceRefl
  ) {}

  /**
   * Shape of a container-typed component: kind (list / map values / optional), value/element {@link
   * Type} (preserved as Type so nested containers like {@code List<Optional<X>>} can be resolved by
   * recursive {@link #autoIso}), and — for {@code Map} — the key class. Two shapes are compatible
   * for auto-lifting when their kinds match and (for {@code MAP_VALUES}) their key classes are
   * equal. The key check matters because {@code Iso.liftMapValues} preserves the source keys;
   * mapping a {@code Map<String, X>} to a {@code Map<Long, Y>} target would silently produce a
   * {@code Map<String, Y>} at runtime, which violates the target's declared key type.
   */
}
