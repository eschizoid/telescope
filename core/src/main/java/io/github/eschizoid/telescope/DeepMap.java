package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.NullDefaults;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Iso;
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
import io.github.eschizoid.telescope.mapping.WriteHint;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.temporal.Temporal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Vector;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

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

  // ---------- Package-private entries (called from Telescope.map / Telescope.mapper) ----------

  static <A, B> Iso<A, B> resolve(final Class<A> source, final Class<B> target, final MapStep[] steps) {
    return resolution(source, target, steps, false).iso;
  }

  static <A, B> Mapper<A, B> resolveMapper(final Class<A> source, final Class<B> target, final MapStep[] steps) {
    // Bidirectional: strict bijection — unmatched fields on EITHER side throw at construction.
    // Round-trip safety depends on every field having a same-name counterpart or an explicit row.
    final var r = resolution(source, target, steps, false);
    // Go through Mapper.create (public, Function-typed) — same call works regardless of whether
    // Mapper sits in this package or moves to conversion/.
    return Mapper.create(r.iso::to, r.iso::from, source, target, r.patchTable);
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
    for (final var step : steps) {
      if (step instanceof Mapping<?, ?> m) overrides.add(m);
      else if (step instanceof WriteHint<?> h) hints.add(h);
      else if (step instanceof NullHint nh) nullHints.add(nh);
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
    populateIso(
      source,
      target,
      overrideTable,
      beanRefl,
      cache,
      topSteps,
      nullStrategy,
      cyclicPairs,
      inProgress,
      lenient
    );
    validateAllHintsConsumed(hintMap, cache);
    final var iso = (Iso<A, B>) Objects.requireNonNull(cache.get(new TypePair(source, target)));
    final var patchTable = new LinkedHashMap<String, Mapper.PatchEntry>();
    topSteps.forEach((tgtName, step) ->
      patchTable.put(tgtName, new Mapper.PatchEntry(step.sourceName, v -> ((Iso<Object, Object>) step.iso).from(v)))
    );
    return new Resolution<>(iso, patchTable);
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
      if (cls.isRecord()) throw new IllegalArgumentException(
        "writeBean hint targets a record class (" +
          cls.getName() +
          "). Records are always reconstructed via the canonical constructor; the hint cannot apply. " +
          "Remove the writeBean(...) row, or move it to the bean side of the mapping."
      );
      if (map.containsKey(cls)) throw new IllegalArgumentException(
        "Duplicate writeBean hint for " +
          cls.getName() +
          ". Each target class may declare at most one writeBean(...) row per Telescope.map(...) call."
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
      if (!(hint instanceof WriteHint.DefaultWriteHint defaultHint)) continue;
      if (defaultStrategy != null) throw new IllegalArgumentException(
        "Duplicate writeBeans(...) default — at most one default write strategy per Telescope.map(...) call."
      );
      defaultStrategy = defaultHint.strategy();
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
      case FIELDS -> Beans.fieldsWriter(raw);
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
        ". Remove the row, or verify the class is actually reached by the source/target structure."
    );
  }

  // ---------- Override grouping ----------

  private static Map<TypePair, List<Mapping<?, ?>>> groupOverridesByPair(
    final Mapping<?, ?>[] overrides,
    final Class<?> topSource,
    final Class<?> topTarget
  ) {
    final var grouped = new HashMap<TypePair, List<Mapping<?, ?>>>();
    for (final var row : overrides) {
      // Rows with null sourceClass / targetClass pin to the top-level pair the user passed to
      // Telescope.mapper(...). Two reasons a class field comes back null:
      //   - Drop(srcAcc) — single-arg form, no explicit target.
      //   - TelescopeTo / FromTelescopeTo / TelescopeToTelescope — the side that's a
      //     Telescope<X, ?> can't expose its root class via SerializedLambda (the lattice is built
      //     from method-ref accessors but the root Class<S> isn't carried at runtime).
      // In both cases the substitution lands the row on the outer (topSource, topTarget) bucket so
      // populateIso picks it up at the outermost (source, target) recursion frame only.
      final Class<?> effectiveSource = row.sourceClass() == null ? topSource : row.sourceClass();
      final Class<?> effectiveTarget = row.targetClass() == null ? topTarget : row.targetClass();
      final var key = new TypePair(effectiveSource, effectiveTarget);
      grouped.computeIfAbsent(key, __ -> new ArrayList<>()).add(row);
    }
    return grouped;
  }

  // ---------- Recursive resolver (writes into the cache) ----------

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
    final boolean lenient
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
      }
      return;
    }
    cache.put(key, null); // reserve slot so cycle-re-entry short-circuits
    inProgress.push(key);
    // The pop lives in finally (at the end of this method) so a thrown IllegalStateException —
    // the strict-bijection guard at the top-level pair — doesn't corrupt the `inProgress` stack.
    // `inProgress.size() > 1` is the lenient-nested gate; a leaked pre-pop frame would silently
    // flip future top-level calls in the same cache into "nested" mode.
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
      final var telescopeFixups = new ArrayList<Mapping<?, ?>>();

      for (final var rawRow : overrides.getOrDefault(key, List.of())) {
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
        // TelescopeTo: flat source accessor → nested target telescope.
        // Soft claim on the source field (from srcAcc) AND the target telescope's first hop name.
        // Multiple rows sharing the same source field or top-level target field all compose.
        if (row instanceof TelescopeTo<?, ?, ?> tRow) {
          telescopeReadsSrc.add(srcField);
          final var firstTgtHop = tRow.targetTelescope().firstHopName();
          if (firstTgtHop != null) telescopeWritesTgt.add(tgtRefl.normalize(firstTgtHop));
          telescopeFixups.add(rawRow);
          continue;
        }
        // FromTelescopeTo: nested source telescope → flat target accessor — soft claim mirror.
        // Soft claim on the target field (from tgtAcc) AND the source telescope's first hop name.
        if (row instanceof FromTelescopeTo<?, ?, ?> fRow) {
          telescopeWritesTgt.add(tgtRefl.normalize(row.targetField()));
          final var firstSrcHop = fRow.sourceTelescope().firstHopName();
          if (firstSrcHop != null) telescopeReadsSrc.add(srcRefl.normalize(firstSrcHop));
          telescopeFixups.add(rawRow);
          continue;
        }
        // TelescopeToTelescope (covers both Kind.BROADCAST from Mapping.to and Kind.ZIP from
        // Mapping.zip): both sides are nested telescopes. Recover the top-level src/tgt field names
        // from each telescope's first hop so auto-recursion can build the top-level structure; the
        // post-fixup then overlays the deep leaf (broadcast or positional, depending on kind).
        if (row instanceof TelescopeToTelescope<?, ?, ?> ttRow) {
          final var firstSrcHop = ttRow.sourceTelescope().firstHopName();
          final var firstTgtHop = ttRow.targetTelescope().firstHopName();
          if (firstSrcHop != null) telescopeReadsSrc.add(srcRefl.normalize(firstSrcHop));
          if (firstTgtHop != null) telescopeWritesTgt.add(tgtRefl.normalize(firstTgtHop));
          telescopeFixups.add(rawRow);
          continue;
        }
        // Constant / Compute rows are target-injection telescope fixups: claim the target
        // telescope's first hop as written, register the row in telescopeFixups, and let the
        // applyForward pass stamp value (Constant) or supplier.get() (Compute) at the location the
        // telescope navigates to. The flat factories (constant(Accessor, X) / compute(Accessor,
        // Supplier)) wrap the accessor in a single-hop telescope at construction time so this loop
        // sees only one shape per kind — no separate flat handler. Backward direction is a no-op:
        // these rows don't contribute to bySourceName, so the source rebuilder ignores the slot
        // entirely on backward (same retraction semantics as Drop on the source side).
        if (row instanceof Constant<?, ?, ?> cRow) {
          final var firstTgtHop = cRow.targetTelescope().firstHopName();
          if (firstTgtHop != null) telescopeWritesTgt.add(tgtRefl.normalize(firstTgtHop));
          telescopeFixups.add(rawRow);
          continue;
        }
        if (row instanceof Compute<?, ?, ?> cpRow) {
          final var firstTgtHop = cpRow.targetTelescope().firstHopName();
          if (firstTgtHop != null) telescopeWritesTgt.add(tgtRefl.normalize(firstTgtHop));
          telescopeFixups.add(rawRow);
          continue;
        }
        // Drop rows claim a source field with no target counterpart — they exist to satisfy the
        // strict source-must-be-claimed pass below when one side carries fields the other doesn't.
        if (row instanceof Drop<?, ?, ?>) {
          if (!claimedSrc.add(srcField)) throw new IllegalArgumentException(
            "Deep map " +
              source.getSimpleName() +
              " → " +
              target.getSimpleName() +
              ": duplicate override row for source field '" +
              srcField +
              "'. Each (source, target) type pair may declare at most one row per source field."
          );
          // Register a backward-only step under the source name so the source-reconstructor
          // (assembleIso's bySourceName loop) produces a placeholder value (null) for the dropped
          // field. The step is NOT registered under byTargetName, so the forward direction omits
          // the source field from the target map entirely.
          bySourceName.put(srcField, new FieldStep(srcField, null, NULLING_ISO));
          continue;
        }
        final var tgtField = tgtRefl.normalize(row.targetField());
        // Fail fast on duplicate target — two rows targeting the same target field would silently
        // overwrite each other in byTargetName and could produce non-bijective forward/backward
        // (each direction using a different correspondence).
        if (!claimedTgt.add(tgtField)) throw new IllegalArgumentException(
          "Deep map " +
            source.getSimpleName() +
            " → " +
            target.getSimpleName() +
            ": duplicate override row for target field '" +
            tgtField +
            "'. Each (source, target) type pair may declare at most one row per target field."
        );
        // Same-source fan-out IS permitted: one source field feeding multiple target fields is a
        // common enterprise pattern (e.g. `businessUnit → cretnUserId AND lastUpdtdUserId` on
        // audit-column rebuilds). Forward direction broadcasts the source value to every target row
        // correctly. Backward direction is non-bijective for the fan-out source field — the last
        // registered row wins the `bySourceName` slot, so backward reconstructs that source field
        // from one target's value. Round-trip equality holds when the user keeps fan-out targets in
        // sync (typically same-typed copies of the same column), and the test pin makes the
        // last-row-wins behaviour explicit so silent ambiguity is impossible.
        claimedSrc.add(srcField);
        final var tgtType = tgtRefl.genericType(target, tgtField);
        final var rawRowIso = fieldIsoOf(row, srcRefl.genericType(source, srcField), tgtType);
        // SameTypedTo rows are pure identity at the leaf — wrap with default-on-null when the
        // mapper's null strategy is DEFAULT so an unset source field lands as the type default
        // instead of null. Other field-iso rows (TypedTransformTo, ForwardOnlyTransformTo, Via)
        // carry user-supplied forward functions and are explicitly NOT wrapped — the user already
        // decided how their lambda handles null. This precedence rule lets toOrElse(...) (a
        // TypedTransformTo) and explicit transforms win over the global hint without any extra
        // marker on the row, which is the simplest "per-row beats per-mapper" semantics.
        final var rowIso = (nullStrategy == NullHint.NullStrategy.DEFAULT && row instanceof SameTypedTo<?, ?, ?>)
          ? wrapDefaultOnNull(rawRowIso, tgtType)
          : rawRowIso;
        final var step = new FieldStep(srcField, tgtField, rowIso);
        byTargetName.put(tgtField, step);
        bySourceName.put(srcField, step);
      }

      final var srcNames = srcRefl.names(source);
      final var srcNameSet = new HashSet<>(List.of(srcNames));
      for (final var name : tgtRefl.names(target)) {
        if (claimedTgt.contains(name)) continue;
        // Telescope-row permissive mode: when ANY telescope row is registered for this pair, target
        // fields with no same-name source get a NULLING_ISO placeholder. The post-fixup overlays it
        // if a TelescopeToTelescope / FromTelescopeTo writes through that field; otherwise the
        // field
        // stays null. Telescope rows can't recover their top-level target field name from a
        // Telescope<B, X> at runtime (generics erased), so we permit the gap instead of failing on
        // every nested write. Without any telescope row, the strict same-name check still fires.
        if (!srcNameSet.contains(name)) {
          // Lenient on nested auto-recursed pairs: when the current call is recursed from
          // computeAutoIso (inProgress has more than just the current pair on the stack), the
          // user never explicitly configured this pair — it was visited because a parent field's
          // genericType is reflectable. An unmatched target field there should stay at its JLS
          // default rather than abort the top-level construction. Strictness is preserved at the
          // TOP-LEVEL pair (`inProgress.size() == 1`) where the user explicitly asked for the
          // mapper; missing fields there ARE a configuration mistake worth surfacing.
          final var isNested = inProgress.size() > 1;
          // `lenient` is set by `resolveForward` — forward-only mappers don't run `backward()`,
          // so the bijection invariant doesn't apply and unmatched target fields silently take
          // the JLS default rather than abort construction. Matches MapStruct's default behaviour
          // and removes "13 drops + 2 constants for a 3-field rename" friction on small-DTO →
          // large-entity migration shapes.
          if (!telescopeFixups.isEmpty() || isNested || lenient) {
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
              new FieldStep(null, name, placeholderIsoFor(fieldType, telescopeWritesTgt.contains(name)))
            );
            continue;
          }
          throw new IllegalStateException(
            "Deep map " +
              source.getSimpleName() +
              " → " +
              target.getSimpleName() +
              ": target " +
              slot(tgtRefl) +
              " '" +
              name +
              "' has no same-name source " +
              slot(srcRefl) +
              ". Add a rename row to(sourceAccessor, targetAccessor) that maps to '" +
              name +
              "'."
          );
        }
        final var step = new FieldStep(
          name,
          name,
          autoIso(
            srcRefl.genericType(source, name),
            tgtRefl.genericType(target, name),
            name,
            overrides,
            beanRefl,
            cache,
            nullStrategy,
            cyclicPairs,
            inProgress
          )
        );
        byTargetName.put(name, step);
        bySourceName.put(name, step);
        claimedSrc.add(name);
      }

      for (final var name : srcNames) {
        if (claimedSrc.contains(name)) continue;
        // Telescope-read source without a same-name target consumer: register a NULLING_ISO
        // backward-only placeholder so the source rebuilder produces null for this field; the
        // backward post-fixup will fill the real value from the target telescope.
        if (telescopeReadsSrc.contains(name)) {
          bySourceName.putIfAbsent(name, new FieldStep(name, null, NULLING_ISO));
          continue;
        }
        // Same permissive mode as the target side: when telescope rows are present, this is a
        // nested auto-recursed pair, OR the resolution is forward-only (lenient), source fields
        // with no consumer fall back to a NULLING placeholder rather than failing.
        if (!telescopeFixups.isEmpty() || inProgress.size() > 1 || lenient) {
          bySourceName.putIfAbsent(name, new FieldStep(name, null, NULLING_ISO));
          continue;
        }
        throw new IllegalStateException(
          "Deep map " +
            source.getSimpleName() +
            " → " +
            target.getSimpleName() +
            ": source " +
            slot(srcRefl) +
            " '" +
            name +
            "' has no same-name target " +
            slot(tgtRefl) +
            ". Add a rename row to(sourceAccessor, targetAccessor) that consumes '" +
            name +
            "'."
        );
      }

      final Iso<S, T> baseIso = assembleIso(source, target, srcRefl, tgtRefl, byTargetName, bySourceName);
      cache.put(
        key,
        telescopeFixups.isEmpty() ? baseIso : wrapWithTelescopeFixups(baseIso, telescopeFixups, srcRefl, source)
      );
      if (topStepsOut != null) topStepsOut.putAll(byTargetName);
    } finally {
      inProgress.pop();
    }
  }

  /**
   * Compose post-fixups on top of the base {@link Iso} produced by {@link #assembleIso}. Four
   * telescope-based row shapes route through this single wrapper; each contributes a forward
   * overlay and a backward overlay built from the lattice's public {@code Telescope.set} / {@code
   * Telescope.read} (and {@code toList} / {@code updateIndexed} for the {@link
   * TelescopeToTelescope.Kind#ZIP} case).
   *
   * <ul>
   *   <li>{@link TelescopeTo} (flat src → nested tgt): forward {@code tgtT.set(t,
   *       srcAcc.apply(s))}; backward rebuilds {@code s} with {@code sourceField} = {@code
   *       tgtT.read(t)}.
   *   <li>{@link FromTelescopeTo} (nested src → flat tgt): forward rebuilds {@code t} with {@code
   *       targetField} = {@code srcT.read(s)}; backward rebuilds {@code s} via {@code srcT.set(s,
   *       tgtAcc.apply(t))}.
   *   <li>{@link TelescopeToTelescope} with {@link TelescopeToTelescope.Kind#BROADCAST} (nested ↔
   *       nested, broadcast): forward {@code tgtT.set(t, srcT.read(s))}; backward {@code
   *       srcT.set(s, tgtT.read(t))}. When either side is many-focus the lattice's intrinsic
   *       broadcast / first-focus semantics apply — no extra machinery here.
   *   <li>{@link TelescopeToTelescope} with {@link TelescopeToTelescope.Kind#ZIP} (nested ↔ nested,
   *       positional N:N): forward reads {@code srcT.toList(s)} and writes positionally via {@code
   *       tgtT.updateIndexed(t, ...)} with cardinality enforcement; backward mirrors.
   * </ul>
   *
   * <p>All reads / writes go through the lattice's public {@link io.github.eschizoid.telescope
   * .Telescope} surface — no new optic primitives, no Iso composition beyond the base.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <S, T> Iso<S, T> wrapWithTelescopeFixups(
    final Iso<S, T> base,
    final List<Mapping<?, ?>> fixups,
    final Reflective srcRefl,
    final Class<S> source
  ) {
    return Iso.of(
      s -> applyForward(base.to(s), s, fixups),
      t -> applyBackward(base.from(t), t, fixups, srcRefl, source)
    );
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <S, T> T applyForward(final T initial, final S s, final List<Mapping<?, ?>> fixups) {
    T t = initial;
    for (final var rawFx : fixups) {
      // Conditional<A, B>(predicate, inner) gates the inner row's forward effect by the source
      // predicate. When the predicate rejects the source, skip the row entirely — the target
      // field keeps whatever the base structural Iso produced. Predicate cast widens the
      // upper-bound wildcard `? super A` against the type-erased source `s`; safe because the
      // mapper's Class<A> verifies s at runtime through the surrounding forward(...) entry.
      final Mapping<?, ?> fx;
      if (rawFx instanceof Conditional<?, ?> cond) {
        final var predicate = (Predicate<Object>) cond.predicate();
        final boolean accepted;
        try {
          accepted = predicate.test(s);
        } catch (final Throwable predicateFailure) {
          // Decorate user-predicate exceptions (including Errors like StackOverflowError on a
          // self-recursive predicate, AssertionError on a `assert` in the body, and
          // NoClassDefFoundError) with the row's inner-kind + source field breadcrumb so the
          // failure points at the user's when(...) site, not at an opaque applyForward stack
          // frame. Matches the self-diagnosing style of Mapping.zip's cardinality check below.
          // Widened from RuntimeException to Throwable because the original catch let Errors
          // propagate raw — the breadcrumb is even more valuable for those.
          final var inner = cond.inner();
          final var innerField = inner.sourceField() == null ? "<telescope>" : inner.sourceField();
          // Include the failure CLASS name — predicate.test() commonly throws NPE on null
          // navigation, where getMessage() returns null and "Predicate failure: null" tells the
          // user nothing. The class name (NullPointerException, ClassCastException, etc.) carries
          // the actionable signal even when the message is null.
          final var failureType = predicateFailure.getClass().getSimpleName();
          final var failureMsg = predicateFailure.getMessage();
          throw new IllegalStateException(
            "Mapping.when(...) predicate threw — inner=" +
              inner.getClass().getSimpleName() +
              " (sourceField=" +
              innerField +
              "). Predicate failure: " +
              failureType +
              (failureMsg == null ? "" : ": " + failureMsg),
            predicateFailure
          );
        }
        if (!accepted) continue;
        fx = cond.inner();
      } else {
        fx = rawFx;
      }
      if (fx instanceof TelescopeTo<?, ?, ?> r) {
        final var srcAcc = (Telescope.Accessor<S, Object>) r.srcAccessor();
        final var tgtT = (Telescope<T, Object>) r.targetTelescope();
        t = tgtT.set(t, srcAcc.apply(s));
      } else if (fx instanceof FromTelescopeTo<?, ?, ?> r) {
        final var srcT = (Telescope<S, Object>) r.sourceTelescope();
        // The target side is a flat accessor; we need to rebuild t with the named target field
        // overridden by srcT.read(s). Delegate to overrideTargetField, which uses the target
        // Reflective.construct the same way the source-side path does in applyBackward.
        t = overrideTargetField(t, r, srcT, s);
      } else if (fx instanceof TelescopeToTelescope<?, ?, ?> r) {
        final var srcT = (Telescope<S, Object>) r.sourceTelescope();
        final var tgtT = (Telescope<T, Object>) r.targetTelescope();
        if (r.kind() == TelescopeToTelescope.Kind.ZIP) {
          final var values = srcT.toList(s);
          final var targetCount = tgtT.count(t);
          if (values.size() != targetCount) throw new IllegalStateException(
            "Mapping.zip: source has " +
              values.size() +
              " focus(es), target has " +
              targetCount +
              " — cardinality must match for positional zip."
          );
          t = tgtT.updateIndexed(t, (i, _ignored) -> values.get(i));
        } else {
          // Lenient: when the source path resolves to an empty focus (null intermediate in a
          // chained bean read, or an Affine miss further down the path), write null to the target
          // field rather than throwing. Downstream type-default handling — where configured —
          // takes over from there.
          t = tgtT.set(t, srcT.find(s).orElse(null));
        }
      } else if (fx instanceof Constant<?, ?, ?> r) {
        final var tgtT = (Telescope<T, Object>) r.targetTelescope();
        t = tgtT.set(t, r.value());
      } else if (fx instanceof Compute<?, ?, ?> r) {
        final var tgtT = (Telescope<T, Object>) r.targetTelescope();
        t = tgtT.set(t, r.supplier().get());
      }
      // non-telescope mappings are not routed through this wrapper
    }
    return t;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <S, T> S applyBackward(
    final S baseS,
    final T t,
    final List<Mapping<?, ?>> fixups,
    final Reflective srcRefl,
    final Class<S> source
  ) {
    // Collect per-field override values keyed by normalized source field name; the rebuild reads
    // through srcRefl.construct and substitutes our overrides per name. Telescope-source fixups
    // (FromTelescopeTo, TelescopeToTelescope) don't have a top-level source field —
    // they apply via srcT.set on the rebuilt baseS, after the name-keyed rebuild finishes.
    final var fieldOverrides = new HashMap<String, Object>();
    for (final var fx : fixups) {
      // Conditional rows are forward-only by design — same retraction semantics as Constant /
      // Compute. The source rebuild leaves the corresponding source field at the baseS value;
      // forward(backward(t)) is intentionally asymmetric for predicate-gated rows.
      if (fx instanceof Conditional<?, ?>) continue;
      if (fx instanceof TelescopeTo<?, ?, ?> r) {
        final var tgtT = (Telescope<T, Object>) r.targetTelescope();
        fieldOverrides.put(srcRefl.normalize(r.sourceField()), tgtT.read(t));
      }
    }
    S s = (S) srcRefl.construct(source, name ->
      fieldOverrides.containsKey(name) ? fieldOverrides.get(name) : srcRefl.read(baseS, name)
    );
    // Telescope-source fixups overlay AFTER the name-keyed rebuild, via srcT.set on s.
    for (final var fx : fixups) {
      if (fx instanceof Conditional<?, ?>) continue;
      if (fx instanceof FromTelescopeTo<?, ?, ?> r) {
        final var srcT = (Telescope<S, Object>) r.sourceTelescope();
        final var tgtAcc = (Telescope.Accessor<T, Object>) r.tgtAccessor();
        s = srcT.set(s, tgtAcc.apply(t));
      } else if (fx instanceof TelescopeToTelescope<?, ?, ?> r) {
        final var srcT = (Telescope<S, Object>) r.sourceTelescope();
        final var tgtT = (Telescope<T, Object>) r.targetTelescope();
        if (r.kind() == TelescopeToTelescope.Kind.ZIP) {
          final var values = tgtT.toList(t);
          final var sourceCount = srcT.count(s);
          if (values.size() != sourceCount) throw new IllegalStateException(
            "Mapping.zip: target has " +
              values.size() +
              " focus(es), source has " +
              sourceCount +
              " — cardinality must match for positional zip."
          );
          s = srcT.updateIndexed(s, (i, _ignored) -> values.get(i));
        } else {
          s = srcT.set(s, tgtT.read(t));
        }
      }
      // TelescopeTo already handled above via fieldOverrides
    }
    return s;
  }

  /**
   * Forward overlay for {@link FromTelescopeTo} — rebuild the target with the named target field
   * overridden by {@code srcTelescope.read(s)}. We can't construct a typed one-hop Telescope on the
   * target side without knowing T's runtime class up-front (generics erased), so we use the target
   * Reflective via the cached structural iso the same way the source-side path does.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <S, T> T overrideTargetField(
    final T t,
    final FromTelescopeTo<?, ?, ?> r,
    final Telescope<S, Object> srcT,
    final S s
  ) {
    final var tgtClass = (Class<T>) t.getClass();
    final var tgtRefl = Reflective.of(tgtClass);
    final var tgtField = tgtRefl.normalize(r.targetField());
    // Lenient: when the source path resolves to an empty focus (null intermediate in a chained
    // bean read, or an Affine miss further down the path), rebuild proceeds with null in the
    // target field rather than aborting the mapper with NoSuchElementException. Downstream type-
    // default handling — where configured — takes over from there.
    final var newValue = srcT.find(s).orElse(null);
    return (T) tgtRefl.construct(tgtClass, name -> name.equals(tgtField) ? newValue : tgtRefl.read(t, name));
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
    final Deque<TypePair> inProgress
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
      inProgress
    );
    // DEFAULT strategy wraps EVERY auto-recursed per-component Iso uniformly: scalar identity,
    // recursive record/bean pair, lifted container, cross-Optional bridge. When the source value
    // is null, the wrapper substitutes the per-leaf-type default from NullDefaults#defaultFor so
    // bean-side targets see a usable value rather than null. Non-DEFAULT strategy short-circuits
    // to the raw Iso for a no-op cost.
    return nullStrategy == NullHint.NullStrategy.DEFAULT ? wrapDefaultOnNull(raw, tgtType) : raw;
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
    final Deque<TypePair> inProgress
  ) {
    // (a) Same generic type → identity Iso.
    if (srcType.equals(tgtType)) return Iso.identity();

    // (a.1) Primitive ↔ wrapper pair → autobox / unbox via JLS-default-safe Iso. Forward
    // unboxes (null-safe — substitutes the primitive default to avoid NPE on the wrapper-to-
    // primitive setter); backward boxes via the wrapper's static valueOf. Matches MapStruct's
    // behaviour for these pairs (it silently autoboxes and uses 0/false/etc. for null).
    if (
      srcType instanceof Class<?> srcClsAB &&
      tgtType instanceof Class<?> tgtClsAB &&
      isPrimitiveWrapperPair(srcClsAB, tgtClsAB)
    ) {
      return primitiveWrapperIso(srcClsAB, tgtClsAB);
    }

    // (a.2) Collection / Map subtype pair. Common in legacy bean codebases: `class
    //       ImageUrls extends ArrayList<ImageUrl>` and `class ImageUrlsBO extends
    //       ArrayList<ImageUrl>` on opposite sides. Neither is identity (different concrete
    //       classes), neither is a parameterised raw `List` / `Map` (so ContainerShape skips
    //       them), and trying to bean-decompose would hit
    //       `MethodHandles.privateLookupIn(ArrayList.class)` rejection. Copy elements via the
    //       target's no-arg constructor + `addAll` / `putAll`.
    //
    //       Collection side is gated on same kind (List↔List, Set↔Set, Queue↔Queue). Copying a
    //       `LinkedList` into a `HashSet` would silently deduplicate; an `ArrayList` into a
    //       `PriorityQueue` would silently reorder by natural ordering. Users who genuinely want
    //       a kind change must declare an explicit `Mapping.via(...)` row.
    //
    //       Map side uses a SortedMap-vs-non-Sorted axis (mirrors the SortedSet split): a
    //       `HashMap ↔ TreeMap` pair over non-Comparable keys would CCE at putAll. Within each
    //       side, iteration-order, comparator, and thread-safety guarantees may still shift
    //       when the concrete kinds differ (e.g. `LinkedHashMap → HashMap` reorders;
    //       `ConcurrentHashMap → HashMap` drops the concurrency contract). The Iso copies
    //       entries verbatim — users with semantic dependencies on the source's concrete type
    //       should declare an explicit row.
    if (srcType instanceof Class<?> srcCC && tgtType instanceof Class<?> tgtCC) {
      if (sameKindCollection(srcCC, tgtCC)) {
        final var iso = collectionCopyIso(srcCC, tgtCC);
        if (iso != null) return iso;
      }
      if (sameKindMap(srcCC, tgtCC)) {
        final var iso = mapCopyIso(srcCC, tgtCC);
        if (iso != null) return iso;
      }
    }

    // (b) Both reflectable (record or bean) → recurse, return cache-reading Iso so cycles work.
    if (srcType instanceof Class<?> srcCls && tgtType instanceof Class<?> tgtCls) {
      if (isReflectable(srcCls) && isReflectable(tgtCls)) {
        // Nested recursion — `isNested` (inProgress.size() > 1) already triggers the lenient gate
        // for unmatched fields regardless of the outer call's strictness. Pass `false` here so the
        // lenient flag's meaning stays anchored to the user-facing top-level call (mapperForward).
        populateIso(srcCls, tgtCls, overrides, beanRefl, cache, null, nullStrategy, cyclicPairs, inProgress, false);
        final var subKey = new TypePair(srcCls, tgtCls);
        return lazyCacheIso(cache, subKey, !cyclicPairs.contains(subKey));
      }
    }

    // (c) Both same-kind containers → recurse on the element TYPE so nested containers work
    //     (List<Optional<X>>, Optional<List<X>>, Map<K, List<X>>, etc.). Scalar/record/container
    //     elements all dispatch through this same autoIso recursion.
    final var srcShape = ContainerShape.of(srcType);
    final var tgtShape = ContainerShape.of(tgtType);

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
    if (srcShape != null && srcShape.kind == ContainerShape.Kind.OPTIONAL && tgtShape == null) {
      final var elementIso = autoIso(
        srcShape.elementType,
        tgtType,
        componentName + "[*]",
        overrides,
        beanRefl,
        cache,
        NullHint.NullStrategy.PROPAGATE,
        cyclicPairs,
        inProgress
      );
      return Iso.liftOptionalToNullable(eraseIso(elementIso));
    }
    if (tgtShape != null && tgtShape.kind == ContainerShape.Kind.OPTIONAL && srcShape == null) {
      final var elementIso = autoIso(
        srcType,
        tgtShape.elementType,
        componentName + "[*]",
        overrides,
        beanRefl,
        cache,
        NullHint.NullStrategy.PROPAGATE,
        cyclicPairs,
        inProgress
      );
      return Iso.liftOptionalToNullable(eraseIso(elementIso)).reverse();
    }

    if (srcShape != null && tgtShape != null && srcShape.kind == tgtShape.kind) {
      // Map<K, X> ↔ Map<K, Y>: keys must match exactly; Iso.liftMapValues preserves source keys.
      if (srcShape.kind == ContainerShape.Kind.MAP_VALUES && !srcShape.keyClass.equals(tgtShape.keyClass)) {
        throw new IllegalStateException(
          "Deep map: component '" +
            componentName +
            "' has incompatible Map key types — source " +
            srcShape.keyClass.getName() +
            " vs target " +
            tgtShape.keyClass.getName() +
            ". Key types must match exactly; auto-lifting preserves the source keys."
        );
      }
      // Element-level recursion passes PROPAGATE — see the cross-Optional branch above for the
      // rationale. NullStrategy.DEFAULT is a field-level semantic and the outer autoIso wraps the
      // lifted result once; double-wrapping the element-level would over-substitute null elements
      // inside the collection (per MapStruct's SET_TO_DEFAULT the gate is at the field, not the
      // element).
      final var elementIso = autoIso(
        srcShape.elementType,
        tgtShape.elementType,
        componentName + "[*]",
        overrides,
        beanRefl,
        cache,
        NullHint.NullStrategy.PROPAGATE,
        cyclicPairs,
        inProgress
      );
      return switch (srcShape.kind) {
        case LIST -> liftListIntoTargetRaw(eraseIso(elementIso), srcShape.rawClass, tgtShape.rawClass);
        case SET -> liftSetIntoTargetRaw(eraseIso(elementIso), srcShape.rawClass, tgtShape.rawClass);
        case MAP_VALUES -> liftMapIntoTargetRaw(eraseIso(elementIso), srcShape.rawClass, tgtShape.rawClass);
        // Optional is final; no subclasses, no allocator needed.
        case OPTIONAL -> Iso.liftOptional(eraseIso(elementIso));
      };
    }

    throw new IllegalStateException(
      "Deep map: component '" +
        componentName +
        "' has incompatible source/target shapes — " +
        srcType.getTypeName() +
        " vs " +
        tgtType.getTypeName() +
        ". Shapes must match: same scalar, both records/beans, or both same-kind container."
    );
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
  private static Iso<?, ?> fieldIsoOf(final Mapping<?, ?> row, final Type srcType, final Type tgtType) {
    // Inline the contributed leaf-level Iso for each row variant. Reading the public components
    // directly keeps Iso (internal) out of the mapping types' public signatures — so the mapping
    // types stay portable across packages without needing @SuppressWarnings("exports").
    if (row instanceof SameTypedTo<?, ?, ?>) return Iso.identity();
    if (row instanceof TypedTransformTo<?, ?, ?, ?> r) return Iso.of((Function) r.forward(), (Function) r.backward());
    if (row instanceof ForwardOnlyTransformTo<?, ?, ?, ?> r) {
      // DEAD-BRANCH-DEFENSIVE: this throwingBackward lambda is unreachable via the public API.
      // Both factory entries Telescope.map(...) and Telescope.mapper(...) call
      // rejectForwardOnlyRows
      // up front; Telescope.mapperForward(...) accepts the row but never invokes the backward leg.
      // The guard remains so a future cross-package construction path that bypasses
      // rejectForwardOnlyRows produces a precise field-naming error rather than silent corruption.
      // NOT a coverage target.
      final String fieldName = r.sourceField();
      final Function<Object, Object> throwingBackward = y -> {
        throw new UnsupportedOperationException(
          "Mapping.toOneWay is forward-only — backward direction is undefined for field '" +
            fieldName +
            "'. Use Telescope.mapperForward(...) for a forward-only mapper, or Mapping.to(src, " +
            "tgt, forward, backward) for an explicit bidirectional row."
        );
      };
      return Iso.of((Function) r.forward(), throwingBackward);
    }
    if (row instanceof Via<?, ?> r) return liftViaIfNeeded(r, srcType, tgtType);
    // DEAD-BRANCH-DEFENSIVE block (rows below): every permit of the sealed Mapping hierarchy that
    // is NOT a per-field leaf Iso is filtered out by populateIso BEFORE this method is called. The
    // checks remain solely as a compile-time exhaustiveness backstop — if a future permit is added
    // to Mapping and populateIso forgets to short-circuit, the corresponding throw here surfaces
    // the routing bug with a clear class name. NOT coverage targets — these can only fire when a
    // routing change in populateIso introduces a regression, at which point the test failure is in
    // populateIso, not here.
    if (row instanceof Drop<?, ?, ?>) throw new IllegalStateException("Drop row should not reach fieldIsoOf");
    if (row instanceof TelescopeTo<?, ?, ?>) throw new IllegalStateException(
      "TelescopeTo row should not reach fieldIsoOf"
    );
    if (row instanceof FromTelescopeTo<?, ?, ?>) throw new IllegalStateException(
      "FromTelescopeTo row should not reach fieldIsoOf"
    );
    if (row instanceof TelescopeToTelescope<?, ?, ?>) throw new IllegalStateException(
      "TelescopeToTelescope row should not reach fieldIsoOf"
    );
    if (row instanceof Constant<?, ?, ?>) throw new IllegalStateException("Constant row should not reach fieldIsoOf");
    if (row instanceof Compute<?, ?, ?>) throw new IllegalStateException("Compute row should not reach fieldIsoOf");
    throw new IllegalStateException("unreachable: Mapping is sealed");
  }

  /**
   * Decide whether the {@link Via} row's nested mapper should be lifted through a container. When
   * the accessor's source/target field types are same-kind containers and the mapper's
   * source/target classes match the element classes, lift via {@link Iso#liftList} / {@link
   * Iso#liftSet} / {@link Iso#liftOptional} / {@link Iso#liftMapValues}. Otherwise the
   * element-level Iso flows through unchanged (scalar / record-pair case).
   */
  @SuppressWarnings("unchecked")
  private static Iso<?, ?> liftViaIfNeeded(final Via<?, ?> row, final Type srcType, final Type tgtType) {
    // Inline: build the element-level Iso from the nested Mapper's public forward/backward, and
    // pull the source/target classes from the same Mapper. Reading the public components inline
    // keeps Iso / Class accessors off Via's public surface.
    final var raw = (Mapper<Object, Object>) row.nested();
    final var elementIso = Iso.of(raw::forward, raw::backward);
    final var mapperSrc = raw.sourceClass();
    final var mapperTgt = raw.targetClass();
    final var srcShape = ContainerShape.of(srcType);
    final var tgtShape = ContainerShape.of(tgtType);
    if (
      srcShape != null &&
      tgtShape != null &&
      srcShape.kind == tgtShape.kind &&
      elementTypeMatches(srcShape.elementType, mapperSrc) &&
      elementTypeMatches(tgtShape.elementType, mapperTgt)
    ) {
      if (srcShape.kind == ContainerShape.Kind.MAP_VALUES && !srcShape.keyClass.equals(tgtShape.keyClass)) {
        throw new IllegalStateException(
          "Deep map via(...): Map key types must match exactly — source " +
            srcShape.keyClass.getName() +
            " vs target " +
            tgtShape.keyClass.getName() +
            ". Key types must match exactly; auto-lifting preserves the source keys."
        );
      }
      return switch (srcShape.kind) {
        case LIST -> liftListIntoTargetRaw(eraseIso(elementIso), srcShape.rawClass, tgtShape.rawClass);
        case SET -> liftSetIntoTargetRaw(eraseIso(elementIso), srcShape.rawClass, tgtShape.rawClass);
        case MAP_VALUES -> liftMapIntoTargetRaw(eraseIso(elementIso), srcShape.rawClass, tgtShape.rawClass);
        // Optional is final; no subclasses, no allocator needed.
        case OPTIONAL -> Iso.liftOptional(eraseIso(elementIso));
      };
    }
    return elementIso;
  }

  private static boolean elementTypeMatches(final Type elementType, final Class<?> mapperClass) {
    return elementType instanceof Class<?> cls && cls.equals(mapperClass);
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
   * Collection ↔ Collection element-copy Iso. The forward instantiates the target collection via
   * {@link Beans#intermediateAllocator(Class)} (cached LMF-bound Supplier) and {@code addAll}'s the
   * source; backward is symmetric. Returns {@code null} when either side has no usable allocator,
   * letting the caller fall through to the next branch (typically the shape-mismatch IAE).
   *
   * <p>No element-type recursion: this branch fires on raw, non-parameterised subtypes (e.g. {@code
   * class ImageUrls extends ArrayList<ImageUrl>}), where the raw class itself carries no runtime
   * generic info. Users whose element types differ across sides should declare an explicit row.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> collectionCopyIso(final Class<?> srcCls, final Class<?> tgtCls) {
    final var srcAlloc = Beans.intermediateAllocator(srcCls);
    final var tgtAlloc = Beans.intermediateAllocator(tgtCls);
    if (srcAlloc.get() == null || tgtAlloc.get() == null) return null;
    return Iso.of(
      src -> {
        if (src == null) return null;
        final var fresh = (Collection) tgtAlloc.get();
        fresh.addAll((Collection<?>) src);
        return fresh;
      },
      tgt -> {
        if (tgt == null) return null;
        final var fresh = (Collection) srcAlloc.get();
        fresh.addAll((Collection<?>) tgt);
        return fresh;
      }
    );
  }

  /** Map ↔ Map element-copy Iso. Mirror of {@link #collectionCopyIso} via {@code putAll}. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> mapCopyIso(final Class<?> srcCls, final Class<?> tgtCls) {
    final var srcAlloc = Beans.intermediateAllocator(srcCls);
    final var tgtAlloc = Beans.intermediateAllocator(tgtCls);
    if (srcAlloc.get() == null || tgtAlloc.get() == null) return null;
    return Iso.of(
      src -> {
        if (src == null) return null;
        final var fresh = (Map) tgtAlloc.get();
        fresh.putAll((Map<?, ?>) src);
        return fresh;
      },
      tgt -> {
        if (tgt == null) return null;
        final var fresh = (Map) srcAlloc.get();
        fresh.putAll((Map<?, ?>) tgt);
        return fresh;
      }
    );
  }

  private static boolean sameKindCollection(final Class<?> a, final Class<?> b) {
    if (!Collection.class.isAssignableFrom(a) || !Collection.class.isAssignableFrom(b)) return false;
    // Require both sides to AGREE on every kind discriminator so the Iso doesn't silently
    // re-interpret container semantics OR throw ClassCastException at addAll time. Without
    // these symmetric checks:
    //   - `LinkedList ↔ ArrayDeque` would match the Queue branch (LinkedList is both List and
    //     Queue) and turn random-access list semantics into FIFO queue semantics.
    //   - `PriorityQueue ↔ ArrayDeque` would match the Queue branch and turn heap-ordered
    //     dequeue into FIFO (or vice-versa).
    //   - `HashSet ↔ TreeSet` over a non-Comparable element type would CCE at forward() time
    //     because the fresh TreeSet's addAll falls through to compareTo on a Comparable-less
    //     element. The SortedSet discriminator rejects that pair before the Iso runs.
    // If either side is a List the other must be one too; same for Set's SortedSet axis; and
    // within the Queue residual, both must agree on whether they're also Deque.
    final var aList = List.class.isAssignableFrom(a);
    final var bList = List.class.isAssignableFrom(b);
    if (aList != bList) return false;
    if (aList) return true;
    final var aSet = Set.class.isAssignableFrom(a);
    final var bSet = Set.class.isAssignableFrom(b);
    if (aSet != bSet) return false;
    if (aSet) return SortedSet.class.isAssignableFrom(a) == SortedSet.class.isAssignableFrom(b);
    if (!Queue.class.isAssignableFrom(a) || !Queue.class.isAssignableFrom(b)) return false;
    return Deque.class.isAssignableFrom(a) == Deque.class.isAssignableFrom(b);
  }

  private static boolean sameKindMap(final Class<?> a, final Class<?> b) {
    if (!Map.class.isAssignableFrom(a) || !Map.class.isAssignableFrom(b)) return false;
    // Same SortedMap discriminator as the Set side: a `HashMap ↔ TreeMap` pair over non-
    // Comparable keys would CCE at putAll time when the fresh TreeMap calls compareTo on the
    // first inserted key. Reject the Sorted/non-Sorted crossing before the Iso runs. Within
    // each side (HashMap ↔ LinkedHashMap ↔ ConcurrentHashMap on non-Sorted; TreeMap ↔
    // ConcurrentSkipListMap on Sorted), differences are iteration-order or thread-safety
    // attribute drops — silent, but documented at the call site.
    //
    // Edge cases the gate intentionally accepts (silent but recoverable via an explicit
    // `Mapping.via(...)` row): `IdentityHashMap ↔ HashMap` (the IdentityHashMap side de-dups
    // equal-but-distinct-reference keys); `WeakHashMap ↔ HashMap` (WeakHashMap GCs keys
    // without strong references).
    return SortedMap.class.isAssignableFrom(a) == SortedMap.class.isAssignableFrom(b);
  }

  /**
   * List-level lift that writes into the target's concrete raw class. Element-wise forward /
   * backward via the {@code elementIso}, allocating fresh source and target instances via {@link
   * Beans#intermediateAllocator}. A {@code List<X> ↔ ArrayList<Y>} pair, or an {@code ArrayList<X>
   * ↔ LinkedList<Y>} pair, produces a result whose runtime class matches the declared target raw
   * class. Falls back to {@link ArrayList} for the raw {@link List} / {@link Collection} interface,
   * where there's no concrete class to allocate.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> liftListIntoTargetRaw(
    final Iso<Object, Object> elementIso,
    final Class<?> srcRaw,
    final Class<?> tgtRaw
  ) {
    final var srcAlloc = listAllocatorFor(srcRaw);
    final var tgtAlloc = listAllocatorFor(tgtRaw);
    return Iso.of(
      src -> {
        if (src == null) return null;
        final var fresh = (Collection) tgtAlloc.get();
        for (final var x : (Collection<?>) src) fresh.add(elementIso.to(x));
        return fresh;
      },
      tgt -> {
        if (tgt == null) return null;
        final var fresh = (Collection) srcAlloc.get();
        for (final var y : (Collection<?>) tgt) fresh.add(elementIso.from(y));
        return fresh;
      }
    );
  }

  /**
   * Set-level lift that writes into the target's concrete raw class. Mirror of {@link
   * #liftListIntoTargetRaw} for Sets. Falls back to {@link LinkedHashSet} (preserving forward
   * iteration order) when the raw class is the {@link Set} interface itself.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> liftSetIntoTargetRaw(
    final Iso<Object, Object> elementIso,
    final Class<?> srcRaw,
    final Class<?> tgtRaw
  ) {
    final var srcAlloc = setAllocatorFor(srcRaw);
    final var tgtAlloc = setAllocatorFor(tgtRaw);
    return Iso.of(
      src -> {
        if (src == null) return null;
        final var fresh = (Collection) tgtAlloc.get();
        for (final var x : (Collection<?>) src) fresh.add(elementIso.to(x));
        return fresh;
      },
      tgt -> {
        if (tgt == null) return null;
        final var fresh = (Collection) srcAlloc.get();
        for (final var y : (Collection<?>) tgt) fresh.add(elementIso.from(y));
        return fresh;
      }
    );
  }

  /**
   * Map-level lift that writes into the target's concrete raw class. Mirror of {@link
   * #liftListIntoTargetRaw} for Maps. Preserves source keys verbatim (matches {@link
   * Iso#liftMapValues}); the calling site already ensured the key classes match. Falls back to
   * {@link LinkedHashMap} when the raw class is the {@link Map} interface itself.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> liftMapIntoTargetRaw(
    final Iso<Object, Object> elementIso,
    final Class<?> srcRaw,
    final Class<?> tgtRaw
  ) {
    final var srcAlloc = mapAllocatorFor(srcRaw);
    final var tgtAlloc = mapAllocatorFor(tgtRaw);
    return Iso.of(
      src -> {
        if (src == null) return null;
        final var fresh = (Map) tgtAlloc.get();
        for (final var e : ((Map<?, ?>) src).entrySet()) fresh.put(e.getKey(), elementIso.to(e.getValue()));
        return fresh;
      },
      tgt -> {
        if (tgt == null) return null;
        final var fresh = (Map) srcAlloc.get();
        for (final var e : ((Map<?, ?>) tgt).entrySet()) fresh.put(e.getKey(), elementIso.from(e.getValue()));
        return fresh;
      }
    );
  }

  // JDK collection classes live in java.base — `Beans.intermediateAllocator` can't bind them
  // via LambdaMetafactory's privateLookupIn (java.base doesn't grant private lookup to app code).
  // Hard-code the common JDK Collection / Map raws so the allocator works for the standard
  // shapes, and fall back to `intermediateAllocator` for user-defined subclasses (where LMF DOES
  // work via the user's own package).
  private static Supplier<Object> listAllocatorFor(final Class<?> raw) {
    if (raw == List.class || raw == Collection.class || raw == ArrayList.class) return ArrayList::new;
    if (raw == LinkedList.class) return LinkedList::new;
    if (raw == ArrayDeque.class) return ArrayDeque::new;
    if (raw == Vector.class) return Vector::new;
    if (raw == Stack.class) return Stack::new;
    if (raw == PriorityQueue.class) return PriorityQueue::new;
    if (raw == LinkedBlockingQueue.class) return LinkedBlockingQueue::new;
    if (raw == CopyOnWriteArrayList.class) return CopyOnWriteArrayList::new;
    final var alloc = Beans.intermediateAllocator(raw);
    if (alloc.get() != null) return alloc;
    // No usable allocator for a JDK java.base class we don't recognise. Falling back to ArrayList
    // would silently write the wrong runtime class into the target field and CCE at the setter.
    // Throw at plan-time with a precise diagnostic instead.
    throw new IllegalStateException(
      "Deep map: no allocator for List subtype " +
        raw.getName() +
        ". Add it to listAllocatorFor (java.base classes can't bind via LambdaMetafactory's " +
        "privateLookupIn) or supply an explicit `Mapping.via(...)` row."
    );
  }

  private static Supplier<Object> setAllocatorFor(final Class<?> raw) {
    if (raw == Set.class || raw == LinkedHashSet.class) return LinkedHashSet::new;
    if (raw == HashSet.class) return HashSet::new;
    if (raw == TreeSet.class) return TreeSet::new;
    if (raw == ConcurrentSkipListSet.class) return ConcurrentSkipListSet::new;
    if (raw == CopyOnWriteArraySet.class) return CopyOnWriteArraySet::new;
    final var alloc = Beans.intermediateAllocator(raw);
    if (alloc.get() != null) return alloc;
    throw new IllegalStateException(
      "Deep map: no allocator for Set subtype " +
        raw.getName() +
        ". Add it to setAllocatorFor (java.base classes can't bind via LambdaMetafactory's " +
        "privateLookupIn) or supply an explicit `Mapping.via(...)` row."
    );
  }

  /**
   * Map-side allocator. {@code IdentityHashMap} and {@code WeakHashMap} are accepted but carry
   * different semantics from a plain {@code HashMap} ({@code IdentityHashMap} uses reference
   * equality for keys, {@code WeakHashMap} GCs keys without strong references) — adopters needing
   * preservation declare an explicit {@code Mapping.via(...)} row. {@code EnumMap} is rejected at
   * plan-time because its no-arg constructor doesn't exist (it needs the {@code Class<K>} arg);
   * adopters must use the codegen path or an explicit row.
   */
  private static Supplier<Object> mapAllocatorFor(final Class<?> raw) {
    if (raw == Map.class || raw == HashMap.class) return HashMap::new;
    if (raw == LinkedHashMap.class) return LinkedHashMap::new;
    if (raw == TreeMap.class) return TreeMap::new;
    if (raw == ConcurrentHashMap.class) return ConcurrentHashMap::new;
    if (raw == ConcurrentSkipListMap.class) return ConcurrentSkipListMap::new;
    if (raw == IdentityHashMap.class) return IdentityHashMap::new;
    if (raw == WeakHashMap.class) return WeakHashMap::new;
    if (raw == EnumMap.class) throw new IllegalStateException(
      "Deep map: EnumMap targets are not supported via auto-Iso lift — EnumMap has no no-arg " +
        "constructor (it needs the Class<K> key class). Use the codegen path or supply an " +
        "explicit `Mapping.via(...)` row that constructs the EnumMap with its key class."
    );
    final var alloc = Beans.intermediateAllocator(raw);
    if (alloc.get() != null) return alloc;
    throw new IllegalStateException(
      "Deep map: no allocator for Map subtype " +
        raw.getName() +
        ". Add it to mapAllocatorFor (java.base classes can't bind via LambdaMetafactory's " +
        "privateLookupIn) or supply an explicit `Mapping.via(...)` row."
    );
  }

  /**
   * True when {@code src} and {@code tgt} are a primitive ↔ wrapper pair (in either direction)
   * referring to the same underlying scalar — e.g. {@code int} / {@code Integer}, {@code boolean} /
   * {@code Boolean}. Same-scalar same-side pairs (already covered by the identity branch) are not
   * matched here.
   */
  private static boolean isPrimitiveWrapperPair(final Class<?> src, final Class<?> tgt) {
    return (src.isPrimitive() && wrap(src) == tgt) || (tgt.isPrimitive() && wrap(tgt) == src);
  }

  /** Wrap a primitive to its boxed class; non-primitives are returned unchanged. */
  private static Class<?> wrap(final Class<?> cls) {
    if (cls == int.class) return Integer.class;
    if (cls == long.class) return Long.class;
    if (cls == double.class) return Double.class;
    if (cls == float.class) return Float.class;
    if (cls == boolean.class) return Boolean.class;
    if (cls == short.class) return Short.class;
    if (cls == byte.class) return Byte.class;
    if (cls == char.class) return Character.class;
    return cls;
  }

  /**
   * Build the per-field Iso for a primitive ↔ wrapper pair. The Iso's {@code to} (forward)
   * substitutes the primitive default when the source value is null, so a wrapper-to-primitive
   * write never NPEs at the setter. {@code from} (backward) is symmetric.
   */
  private static Iso<Object, Object> primitiveWrapperIso(final Class<?> src, final Class<?> tgt) {
    final var fwdDefault = tgt.isPrimitive() ? primitiveDefault(tgt) : null;
    final var bwdDefault = src.isPrimitive() ? primitiveDefault(src) : null;
    return Iso.of(v -> v == null ? fwdDefault : v, v -> v == null ? bwdDefault : v);
  }

  /** A record or any non-scalar class — anything Reflective can drive. */
  private static boolean isReflectable(final Class<?> cls) {
    if (cls.isRecord()) return true;
    if (cls.isPrimitive()) return false;
    if (cls.isArray()) return false;
    if (cls.isEnum()) return false;
    if (cls.isInterface()) return false;
    // Common scalars that should NOT be treated as reflectable beans. CharSequence covers String,
    // StringBuilder, StringBuffer, and any other implementation — assignableFrom catches subtypes.
    if (CharSequence.class.isAssignableFrom(cls)) return false;
    if (Number.class.isAssignableFrom(cls)) return false;
    if (cls == Boolean.class || cls == Character.class) return false;
    if (Temporal.class.isAssignableFrom(cls)) return false;
    return !UUID.class.isAssignableFrom(cls);
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
    final var srcHolderReaders = Telescope.holderReadersFor(source, srcNames);
    final var srcHolderConstructor = Telescope.holderConstructorFor(source);
    final var tgtNames = tgtRefl.names(target);
    final var tgtHolderReaders = Telescope.holderReadersFor(target, tgtNames);
    final var tgtHolderConstructor = Telescope.holderConstructorFor(target);
    // Fused-source-and-remap: bypass the source-side Object[] intermediate. The previous shape
    // ran S → Object[srcArity] (srcReader) → Object[tgtArity] (remap) → T (tgtBuilder) — two
    // intermediate arrays + three Iso.then virtual dispatches per call. The fused body inlines
    // all three stages: target Object[tgtArity] alloc, then for each target slot pull the source
    // value directly from srcReaders[fwdSrcPos[i]].apply(s) and apply the per-slot Iso. Saves one
    // alloc + 5 array writes + 5 array reads + 2 virtual Iso.then hops per call. Identity-Iso
    // short-circuit preserved against the cached singleton from Iso.identity().
    final var srcReaders = srcRefl.positionalReaders(source, srcHolderReaders);
    final var tgtReaders = tgtRefl.positionalReaders(target, tgtHolderReaders);
    final var tgtBuilderFn = tgtRefl.positionalBuilder(target, tgtHolderReaders, tgtHolderConstructor);
    final var srcBuilderFn = srcRefl.positionalBuilder(source, srcHolderReaders, srcHolderConstructor);
    final var slotMaps = buildSlotMaps(byTargetName, bySourceName, srcNames, tgtNames);
    final Iso<Object, Object> identity = Iso.identity();
    return Iso.of(
      s -> {
        if (s == null) return null;
        final var tgtArr = new Object[slotMaps.tgtArity()];
        for (var i = 0; i < slotMaps.tgtArity(); i++) {
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

  /**
   * Position-indexed variant of {@link #remapIso}. Precomputes {@code int[]} slot maps and a
   * per-position {@code Iso[]} once at type-pair build time so the hot loop is one array-index +
   * one virtual {@code Iso#to}/{@code Iso#from} per field. Sentinel slot {@code -1} for "no
   * corresponding source/target field" (placeholder rows where {@code step.sourceName} or {@code
   * step.targetName} is null) — the value defaults to {@code null}, matching the prior {@code
   * Map.get(missingKey)} semantics.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<Object[], Object[]> remapIsoArr(
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
    for (var i = 0; i < tgtArity; i++) {
      final var step = byTargetName.get(tgtNames[i]);
      if (step == null) {
        fwdSrcPos[i] = -1;
        fwdIso[i] = Iso.identity();
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
        bwdIso[i] = Iso.identity();
      } else {
        final var tgtPos = step.targetName == null ? null : tgtIndex.get(step.targetName);
        bwdTgtPos[i] = tgtPos == null ? -1 : tgtPos;
        bwdIso[i] = (Iso<Object, Object>) step.iso;
      }
    }
    // Cache the identity sentinel in a local so the reference-equality check JIT-inlines on the hot
    // path. Auto-mapped same-typed fields hold this exact instance (see Iso.identity), so the
    // short-circuit skips the virtual to/from dispatch on every pure-pass-through slot — the
    // dominant case for flat conversions.
    final Iso<Object, Object> identity = Iso.identity();
    return Iso.of(
      srcArr -> {
        final var out = new Object[tgtArity];
        for (var i = 0; i < tgtArity; i++) {
          final var sp = fwdSrcPos[i];
          final var v = sp < 0 ? null : srcArr[sp];
          final var iso = fwdIso[i];
          out[i] = iso == identity ? v : iso.to(v);
        }
        return out;
      },
      tgtArr -> {
        final var out = new Object[srcArity];
        for (var i = 0; i < srcArity; i++) {
          final var tp = bwdTgtPos[i];
          final var v = tp < 0 ? null : tgtArr[tp];
          final var iso = bwdIso[i];
          out[i] = iso == identity ? v : iso.from(v);
        }
        return out;
      }
    );
  }

  private static Map<String, Integer> indexMap(final String[] names) {
    final var m = new HashMap<String, Integer>(names.length * 2);
    for (var i = 0; i < names.length; i++) m.put(names[i], i);
    return m;
  }

  /**
   * Key + value remap between a source-keyed and a target-keyed structural map. Forward: for each
   * target name, look up the source name via the step table and apply the per-field {@code Iso}
   * forward; key the result under the target name. Backward: mirror image, with per-field {@code
   * Iso.from} and source-name keying.
   */
  @SuppressWarnings("unchecked")
  private static Iso<Map<String, Object>, Map<String, Object>> remapIso(
    final Map<String, FieldStep> byTargetName,
    final Map<String, FieldStep> bySourceName
  ) {
    return Iso.of(
      srcMap -> {
        final var out = new LinkedHashMap<String, Object>();
        for (final var entry : byTargetName.entrySet()) {
          final var step = entry.getValue();
          out.put(entry.getKey(), ((Iso<Object, Object>) step.iso).to(srcMap.get(step.sourceName)));
        }
        return out;
      },
      tgtMap -> {
        final var out = new LinkedHashMap<String, Object>();
        for (final var entry : bySourceName.entrySet()) {
          final var step = entry.getValue();
          out.put(entry.getKey(), ((Iso<Object, Object>) step.iso).from(tgtMap.get(step.targetName)));
        }
        return out;
      }
    );
  }

  /**
   * Wrap an {@link Iso} so {@code null} on either side short-circuits to {@code null}. The
   * structural-iso decomposition would otherwise NPE on {@code structuralIso(...).from(null)} (the
   * read loop dereferences the instance); preserving the prior null-pass-through behavior at the
   * outermost layer keeps DeepMap drop-in compatible with the prior {@code assembleIso}.
   */
  private static <A, B> Iso<A, B> nullable(final Iso<A, B> inner) {
    return Iso.of(a -> a == null ? null : inner.to(a), b -> b == null ? null : inner.from(b));
  }

  // ---------- Cycle-safe cache reader ----------

  /**
   * Per-thread identity-based seen sets for cycle interruption. The forward / backward maps are
   * tracked separately so a forward call doesn't poison the backward traversal of the same object
   * graph. {@link #lazyCacheIso} consults the matching set on entry: re-entry on the same instance
   * returns {@code null}, snipping the cycle in the output graph rather than recursing forever.
   *
   * <p>This is a value-level guard on top of DeepMap's existing type-level cycle handling (the
   * {@link TypePair} cache, which terminates {@code Iso} construction). Type-level guards stop the
   * processor from re-entering an in-progress pair during build; the value-level guards here stop
   * runtime traversal from recursing through a bidirectional persistence association (e.g.,
   * Hibernate's {@code @ManyToOne manager} + {@code @OneToMany(mappedBy="manager") reports})
   * forming a literal value cycle in the hydrated graph.
   *
   * <p><b>Semantics on revisit.</b> The first encounter of an instance traverses normally and
   * records it in the seen set. A subsequent encounter on the same traversal returns {@code null} —
   * the cycle is severed at the second occurrence. For a bidirectional self-association like {@code
   * bob.manager == alice && alice.reports.contains(bob)}, this means the result is finite: the
   * recursive {@code reports} list still includes the leaf entries, but {@code bob.manager
   * .reports.contains(bob)} collapses to {@code bob.manager.reports} where the inner {@code bob}
   * becomes {@code null}.
   *
   * <p>The seen sets clear when the outer {@link Iso#to} / {@link Iso#from} call unwinds, so
   * subsequent independent {@code mapper.forward(otherTree)} invocations start fresh. The outermost
   * guard belongs to whichever {@code lazyCacheIso} is reached first — re-entry into the same
   * lazyCacheIso while still inside an outer call is what we're guarding against.
   */
  private static final ThreadLocal<IdentityHashMap<Object, Boolean>> FORWARD_SEEN = ThreadLocal.withInitial(
    IdentityHashMap::new
  );

  private static final ThreadLocal<IdentityHashMap<Object, Boolean>> BACKWARD_SEEN = ThreadLocal.withInitial(
    IdentityHashMap::new
  );

  @SuppressWarnings("unchecked")
  private static Iso<?, ?> lazyCacheIso(
    final Map<TypePair, Iso<?, ?>> cache,
    final TypePair key,
    final boolean acyclic
  ) {
    // Acyclic-bypass: when the static type graph has no path from this pair back to itself, the
    // value-level cycle guard cannot fire. Skip the ThreadLocal probe + IdentityHashMap insert
    // entirely — ~15 ns saved per nested hop. Verified at populateIso time via the cyclicPairs
    // tracker; if a downstream change introduces a back-edge that the analysis can't see (e.g. an
    // Object-typed component holding an instance-level cycle), the bypass would be unsafe — but the
    // structural typing already prevents DeepMap from descending into Object-typed fields.
    if (acyclic) {
      return Iso.of(
        v -> v == null ? null : ((Iso<Object, Object>) cache.get(key)).to(v),
        v -> v == null ? null : ((Iso<Object, Object>) cache.get(key)).from(v)
      );
    }
    return Iso.of(
      v -> cycleSafe(FORWARD_SEEN, v, x -> ((Iso<Object, Object>) cache.get(key)).to(x)),
      v -> cycleSafe(BACKWARD_SEEN, v, x -> ((Iso<Object, Object>) cache.get(key)).from(x))
    );
  }

  /**
   * Run {@code body} on {@code value} guarded by the per-thread identity-seen set in {@code
   * seenRef}. Re-entry on the same instance returns {@code null} (cycle interruption). Cleans up
   * the seen set when the outermost call unwinds so subsequent independent traversals start fresh.
   */
  private static <X, Y> Y cycleSafe(
    final ThreadLocal<IdentityHashMap<Object, Boolean>> seenRef,
    final X value,
    final Function<X, Y> body
  ) {
    if (value == null) return null;
    final var seen = seenRef.get();
    final boolean outermost = seen.isEmpty();
    if (seen.put(value, Boolean.TRUE) != null) return null; // re-entry on this instance — sever the cycle
    try {
      return body.apply(value);
    } finally {
      if (outermost) seen.clear(); // independent next call starts fresh
    }
  }

  @SuppressWarnings("unchecked")
  private static <X, Y> Iso<X, Y> eraseIso(final Iso<?, ?> iso) {
    return (Iso<X, Y>) iso;
  }

  // ---------- Inner types ----------

  private record TypePair(Class<?> source, Class<?> target) {}

  /**
   * One per-component step: source/target field names + the {@link Iso} between their leaf types.
   */
  private record FieldStep(String sourceName, String targetName, Iso<?, ?> iso) {}

  /**
   * Placeholder Iso used by {@code Mapping.drop(srcAccessor)}'s backward pass — both directions
   * return {@code null}. Only ever invoked in the {@link #remapIso} backward loop for source-only
   * fields that have no target counterpart; the forward direction skips the field entirely.
   */
  private static final Iso<Object, Object> NULLING_ISO = Iso.of(__ -> null, __ -> null);

  /**
   * Forward-only iso that materialises a fresh default-tree instance of {@code type} on every
   * forward call. Used as the placeholder for telescope-row-claimed target fields that have no
   * same-name source counterpart — the post-fixup overlay descends into the allocated instance and
   * writes the leaf, so a fully-flat source can be lifted into a deeply-nested target without
   * per-hop allocation glue.
   *
   * <p>Records recurse via their canonical constructor with default component values. Beans
   * (JavaBean shape) get a fresh instance from their public no-arg constructor. Anything without a
   * usable construction strategy falls back to {@code null} — the user will see the same downstream
   * null the unannotated path produces today, no worse.
   */
  private static Iso<Object, Object> defaultAllocatorIso(final Class<?> type) {
    return Iso.of(__ -> recursiveDefault(type), __ -> null);
  }

  /**
   * Construct a default-tree instance of {@code type} — primitives get their JLS default (0, false,
   * etc.), records recurse via their canonical constructor with the same scheme, beans get a fresh
   * instance from their public no-arg constructor (uninitialised fields default to null/zero, which
   * the telescope-row write then overwrites). Anything else returns {@code null}.
   *
   * <p>Cycles between record types can't arise in practice: each canonical ctor needs every other
   * type already constructible, so a record cycle would fail at compile time. Bean cycles are
   * possible in principle but the no-arg ctor doesn't recurse into fields, so a self-referencing
   * bean is handled with a single allocation regardless of its field shape.
   */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static Object recursiveDefault(final Class<?> type) {
    if (type.isPrimitive()) return primitiveDefault(type);
    if (type.isRecord()) {
      final var comps = type.getRecordComponents();
      final var byName = new HashMap<String, Object>(comps.length);
      for (final var comp : comps) byName.put(comp.getName(), recursiveDefault(comp.getType()));
      return Records.construct((Class) type, byName::get);
    }
    // Bean intermediate: try the public no-arg ctor first, falling back to the static builder()
    // pattern (Lombok @Builder, Immutables-style). Skip JDK scalars / containers entirely so the
    // records path stays unchanged. Telescope-row writes go through the bean's setters at each
    // hop, so each intermediate just needs to be non-null; the setters overwrite the
    // default-initialised fields. If neither strategy works, the cached supplier yields null —
    // same behaviour as before bean-intermediate support, but no per-call
    // `getDeclaredConstructor` / `getMethod("builder")` reflection: both shapes are LMF-cached
    // per class via {@link Beans#intermediateAllocator}.
    if (beanIntermediateAllocatable(type)) {
      return Beans.intermediateAllocator(type).get();
    }
    return null;
  }

  private static Object primitiveDefault(final Class<?> p) {
    if (p == int.class) return 0;
    if (p == long.class) return 0L;
    if (p == boolean.class) return false;
    if (p == double.class) return 0.0;
    if (p == float.class) return 0.0f;
    if (p == byte.class) return (byte) 0;
    if (p == short.class) return (short) 0;
    if (p == char.class) return (char) 0;
    return null;
  }

  /**
   * Type-aware placeholder Iso for the permissive-mode block in {@link #populateIso}. Picks the
   * right "missing source field" filler based on the target field's type and whether a telescope
   * row claims the field as its first hop.
   */
  private static Iso<Object, Object> placeholderIsoFor(
    final Class<?> fieldType,
    final boolean claimedByTelescopeWrite
  ) {
    if (fieldType == null) return NULLING_ISO;
    if (claimedByTelescopeWrite && (fieldType.isRecord() || beanIntermediateAllocatable(fieldType))) {
      return defaultAllocatorIso(fieldType);
    }
    if (fieldType.isPrimitive()) {
      final var value = primitiveDefault(fieldType);
      return Iso.of(__ -> value, __ -> value);
    }
    return NULLING_ISO;
  }

  // True when the bean is plausibly an intermediate-allocatable user-domain type — has either a
  // public no-arg constructor or a static no-arg builder() method (Lombok @Builder / Immutables).
  // Excludes JDK scalars / containers that happen to have public no-arg ctors we don't want to
  // materialise as defaults.
  private static boolean beanIntermediateAllocatable(final Class<?> type) {
    if (type.isPrimitive() || type.isInterface() || type.isArray()) return false;
    if (type == String.class || Number.class.isAssignableFrom(type) || type == Boolean.class) return false;
    try {
      final var ctor = type.getDeclaredConstructor();
      if (Modifier.isPublic(ctor.getModifiers())) return true;
    } catch (final NoSuchMethodException ignored) {
      // try the builder path next
    }
    try {
      final var builderMethod = type.getMethod("builder");
      return Modifier.isStatic(builderMethod.getModifiers()) && Modifier.isPublic(builderMethod.getModifiers());
    } catch (final NoSuchMethodException ignored) {
      return false;
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
   * Bundled return from {@link #resolution(Class, Class, MapStep...)} — the Iso and the patch
   * table.
   */
  private record Resolution<A, B>(Iso<A, B> iso, Map<String, Mapper.PatchEntry> patchTable) {}

  /**
   * Shape of a container-typed component: kind (list / map values / optional), value/element {@link
   * Type} (preserved as Type so nested containers like {@code List<Optional<X>>} can be resolved by
   * recursive {@link #autoIso}), and — for {@code Map} — the key class. Two shapes are compatible
   * for auto-lifting when their kinds match and (for {@code MAP_VALUES}) their key classes are
   * equal. The key check matters because {@code Iso.liftMapValues} preserves the source keys;
   * mapping a {@code Map<String, X>} to a {@code Map<Long, Y>} target would silently produce a
   * {@code Map<String, Y>} at runtime, which violates the target's declared key type.
   */
  private record ContainerShape(Kind kind, Type elementType, Class<?> keyClass, Class<?> rawClass) {
    enum Kind {
      LIST,
      SET,
      MAP_VALUES,
      OPTIONAL,
    }

    static ContainerShape of(final Type t) {
      if (!(t instanceof ParameterizedType pt)) return null;
      if (!(pt.getRawType() instanceof Class<?> raw)) return null;
      // Optional is final; no subtypes possible — keep exact-match.
      if (raw == Optional.class) return new ContainerShape(Kind.OPTIONAL, pt.getActualTypeArguments()[0], null, raw);
      // List / Set / Map: accept any subtype of the interface. The raw class is carried so the
      // autoIso lift can allocate the target's concrete class (e.g. `List<X>` ↔ `ArrayList<Y>`
      // — both shapes match LIST, but the lifted Iso has to write into a fresh ArrayList<Y>
      // when the target field is `ArrayList<Y>`).
      if (List.class.isAssignableFrom(raw)) return new ContainerShape(
        Kind.LIST,
        pt.getActualTypeArguments()[0],
        null,
        raw
      );
      if (Set.class.isAssignableFrom(raw)) return new ContainerShape(
        Kind.SET,
        pt.getActualTypeArguments()[0],
        null,
        raw
      );
      if (Map.class.isAssignableFrom(raw)) {
        final var keyArg = pt.getActualTypeArguments()[0];
        if (!(keyArg instanceof Class<?> keyCls)) return null;
        return new ContainerShape(Kind.MAP_VALUES, pt.getActualTypeArguments()[1], keyCls, raw);
      }
      return null;
    }
  }
}
