package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.mapping.Drop;
import io.github.eschizoid.telescope.mapping.MapStep;
import io.github.eschizoid.telescope.mapping.Mapping;
import io.github.eschizoid.telescope.mapping.MappingInternals;
import io.github.eschizoid.telescope.mapping.SameTypedTo;
import io.github.eschizoid.telescope.mapping.TelescopeTo;
import io.github.eschizoid.telescope.mapping.TypedTransformTo;
import io.github.eschizoid.telescope.mapping.Via;
import io.github.eschizoid.telescope.mapping.WriteHint;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
    return resolution(source, target, steps).iso;
  }

  static <A, B> Mapper<A, B> resolveMapper(final Class<A> source, final Class<B> target, final MapStep[] steps) {
    final var r = resolution(source, target, steps);
    // Go through Mapper.create (public, Function-typed) — same call works regardless of whether
    // Mapper sits in this package or moves to conversion/.
    return Mapper.create(r.iso::to, r.iso::from, source, target, r.patchTable);
  }

  // ---------- Resolution (shared by both public entries) ----------

  @SuppressWarnings("unchecked")
  private static <A, B> Resolution<A, B> resolution(
    final Class<A> source,
    final Class<B> target,
    final MapStep[] steps
  ) {
    final var overrides = new ArrayList<Mapping<?, ?>>();
    final var hints = new ArrayList<WriteHint<?>>();
    for (final var step : steps) {
      if (step instanceof Mapping<?, ?> m) overrides.add(m);
      else if (step instanceof WriteHint<?> h) hints.add(h);
    }
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
    populateIso(source, target, overrideTable, beanRefl, cache, topSteps);
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
   * Pull out the single optional {@link WriteHint#writeBeans(WriteHint.WriteStrategy)
   * writeBeans(…)} default strategy. Returns {@code null} when no default is supplied; throws on
   * duplicates.
   */
  private static WriteHint.WriteStrategy extractDefaultStrategy(final List<WriteHint<?>> hints) {
    WriteHint.WriteStrategy defaultStrategy = null;
    for (final var hint : hints) {
      if (!(hint instanceof WriteHint.DefaultWriteHint(WriteHint.WriteStrategy strat))) continue;
      if (defaultStrategy != null) throw new IllegalArgumentException(
        "Duplicate writeBeans(...) default — at most one default write strategy per Telescope.map(...) call."
      );
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
      final var internals = internalsOf(row);
      // Drop rows have no target accessor. The single-arg drop(srcAcc) factory binds the row to
      // the top-level (source, target) pair the user passed to Telescope.map(...); the two-arg
      // drop(srcAcc, target) factory binds it to whatever nested (source, target) pair the user
      // names explicitly. Internals.targetClass() returns null for the single-arg form and the
      // explicit target Class for the two-arg form.
      final Class<?> effectiveTarget =
        row instanceof Drop<?, ?, ?> && internals.targetClass() == null ? topTarget : internals.targetClass();
      final var key = new TypePair(internals.sourceClass(), effectiveTarget);
      grouped.computeIfAbsent(key, _ -> new ArrayList<>()).add(row);
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
    final Map<String, FieldStep> topStepsOut
  ) {
    final var key = new TypePair(source, target);
    if (cache.containsKey(key)) return;
    cache.put(key, null); // reserve slot so cycle-re-entry short-circuits

    final var srcRefl = pickReflective(source, beanRefl);
    final var tgtRefl = pickReflective(target, beanRefl);

    final var byTargetName = new LinkedHashMap<String, FieldStep>();
    final var bySourceName = new LinkedHashMap<String, FieldStep>();
    final var claimedTgt = new HashSet<String>();
    final var claimedSrc = new HashSet<String>();
    final var telescopeFixups = new ArrayList<TelescopeTo<?, ?, ?>>();

    for (final var row : overrides.getOrDefault(key, List.of())) {
      // Normalize raw method names per side — record::name stays "name", bean::getName becomes
      // "name".
      final var internals = internalsOf(row);
      final var srcField = srcRefl.normalize(internals.sourceField());
      // TelescopeTo rows claim a source field and register a backward-side placeholder that the
      // outer post-fixup (see wrapWithTelescopeFixups below) overwrites with the actual leaf value
      // read from the target telescope. The forward direction is also handled by the outer wrap,
      // which calls targetTelescope.set after the base assembleIso produces a base target.
      if (row instanceof TelescopeTo<?, ?, ?> tRow) {
        if (!claimedSrc.add(srcField)) throw new IllegalArgumentException(
          "Deep map " +
            source.getSimpleName() +
            " → " +
            target.getSimpleName() +
            ": duplicate override row for source field '" +
            srcField +
            "'. Each (source, target) type pair may declare at most one row per source field."
        );
        bySourceName.put(srcField, new FieldStep(srcField, null, NULLING_ISO));
        telescopeFixups.add(tRow);
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
      final var tgtField = tgtRefl.normalize(internals.targetField());
      // Fail fast on duplicates within this type-pair — two rows that target the same source or
      // target field would silently overwrite each other in byTargetName/bySourceName and could
      // produce non-bijective forward/backward (each direction using a different correspondence).
      if (!claimedTgt.add(tgtField)) throw new IllegalArgumentException(
        "Deep map " +
          source.getSimpleName() +
          " → " +
          target.getSimpleName() +
          ": duplicate override row for target field '" +
          tgtField +
          "'. Each (source, target) type pair may declare at most one row per target field."
      );
      if (!claimedSrc.add(srcField)) throw new IllegalArgumentException(
        "Deep map " +
          source.getSimpleName() +
          " → " +
          target.getSimpleName() +
          ": duplicate override row for source field '" +
          srcField +
          "'. Each (source, target) type pair may declare at most one row per source field."
      );
      final var rowIso = fieldIsoOf(row, srcRefl.genericType(source, srcField), tgtRefl.genericType(target, tgtField));
      final var step = new FieldStep(srcField, tgtField, rowIso);
      byTargetName.put(tgtField, step);
      bySourceName.put(srcField, step);
    }

    final var srcNames = srcRefl.names(source);
    final var srcNameSet = new HashSet<>(List.of(srcNames));
    for (final var name : tgtRefl.names(target)) {
      if (claimedTgt.contains(name)) continue;
      if (!srcNameSet.contains(name)) throw new IllegalStateException(
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
      final var step = new FieldStep(
        name,
        name,
        autoIso(srcRefl.genericType(source, name), tgtRefl.genericType(target, name), name, overrides, beanRefl, cache)
      );
      byTargetName.put(name, step);
      bySourceName.put(name, step);
      claimedSrc.add(name);
    }

    for (final var name : srcNames) {
      if (!claimedSrc.contains(name)) throw new IllegalStateException(
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
  }

  /**
   * Compose the {@link TelescopeTo} post-fixups on top of the base {@link Iso} produced by {@link
   * #assembleIso}. Forward: after the base produces a target {@code T}, each fixup's {@code
   * targetTelescope.set(t, srcAccessor.apply(s))} overlays the leaf at the telescope's terminal
   * focus. Backward: after the base produces a source {@code S}, each fixup reads the value at the
   * target telescope and overwrites the source field via the source-side reflective rebuild.
   *
   * <p>Uses {@code Telescope.set} and {@code Telescope.read} from the optics lattice's public
   * surface for the actual leaf reads/writes — no new lattice machinery introduced.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <S, T> Iso<S, T> wrapWithTelescopeFixups(
    final Iso<S, T> base,
    final List<TelescopeTo<?, ?, ?>> fixups,
    final Reflective srcRefl,
    final Class<S> source
  ) {
    return Iso.of(
      s -> {
        T t = base.to(s);
        for (final var fx : fixups) {
          final var srcAcc = (io.github.eschizoid.telescope.Telescope.Accessor<S, Object>) fx.srcAccessor();
          final var tgtT = (io.github.eschizoid.telescope.Telescope<T, Object>) fx.targetTelescope();
          t = tgtT.set(t, srcAcc.apply(s));
        }
        return t;
      },
      t -> {
        final S baseS = base.from(t);
        return (S) srcRefl.construct(source, name -> {
          for (final var fx : fixups) {
            if (srcRefl.normalize(fx.sourceField()).equals(name)) {
              final var tgtT = (io.github.eschizoid.telescope.Telescope<T, Object>) fx.targetTelescope();
              return tgtT.read(t);
            }
          }
          return srcRefl.read(baseS, name);
        });
      }
    );
  }

  // ---------- Per-component auto resolution ----------

  private static Iso<?, ?> autoIso(
    final Type srcType,
    final Type tgtType,
    final String componentName,
    final Map<TypePair, List<Mapping<?, ?>>> overrides,
    final Reflective beanRefl,
    final Map<TypePair, Iso<?, ?>> cache
  ) {
    // (a) Same generic type → identity Iso.
    if (srcType.equals(tgtType)) return Iso.identity();

    // (b) Both reflectable (record or bean) → recurse, return cache-reading Iso so cycles work.
    if (srcType instanceof Class<?> srcCls && tgtType instanceof Class<?> tgtCls) {
      if (isReflectable(srcCls) && isReflectable(tgtCls)) {
        populateIso(srcCls, tgtCls, overrides, beanRefl, cache, null);
        return lazyCacheIso(cache, new TypePair(srcCls, tgtCls));
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
    if (srcShape != null && srcShape.kind == ContainerShape.Kind.OPTIONAL && tgtShape == null) {
      final var elementIso = autoIso(srcShape.elementType, tgtType, componentName + "[*]", overrides, beanRefl, cache);
      return Iso.liftOptionalToNullable(eraseIso(elementIso));
    }
    if (tgtShape != null && tgtShape.kind == ContainerShape.Kind.OPTIONAL && srcShape == null) {
      final var elementIso = autoIso(srcType, tgtShape.elementType, componentName + "[*]", overrides, beanRefl, cache);
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
      final var elementIso = autoIso(
        srcShape.elementType,
        tgtShape.elementType,
        componentName + "[*]",
        overrides,
        beanRefl,
        cache
      );
      return switch (srcShape.kind) {
        case LIST -> Iso.liftList(eraseIso(elementIso));
        case SET -> Iso.liftSet(eraseIso(elementIso));
        case MAP_VALUES -> Iso.liftMapValues(eraseIso(elementIso));
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
    return switch (row) {
      // Inline the contributed leaf-level Iso for each row variant. Reading the public components
      // directly keeps Iso (internal) out of the mapping types' public signatures — so the mapping
      // types stay portable across packages without needing @SuppressWarnings("exports").
      case SameTypedTo<?, ?, ?> _ -> Iso.identity();
      case TypedTransformTo<?, ?, ?, ?> r -> Iso.of((Function) r.forward(), (Function) r.backward());
      case Via<?, ?> r -> liftViaIfNeeded(r, srcType, tgtType);
      // Drop rows never reach this method — populateIso short-circuits on `instanceof Drop` before
      // calling fieldIsoOf. The case is here only to make the switch exhaustive for the sealed
      // hierarchy; reaching it indicates a routing bug above.
      case Drop<?, ?, ?> _ -> throw new IllegalStateException("Drop row should not reach fieldIsoOf");
      // TelescopeTo rows never reach this method either — populateIso short-circuits on
      // `instanceof TelescopeTo` before calling fieldIsoOf; the row applies as a post-fixup at the
      // outer pair, not as a per-field leaf Iso.
      case TelescopeTo<?, ?, ?> _ -> throw new IllegalStateException("TelescopeTo row should not reach fieldIsoOf");
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
        case LIST -> Iso.liftList(eraseIso(elementIso));
        case SET -> Iso.liftSet(eraseIso(elementIso));
        case MAP_VALUES -> Iso.liftMapValues(eraseIso(elementIso));
        case OPTIONAL -> Iso.liftOptional(eraseIso(elementIso));
      };
    }
    return elementIso;
  }

  private static boolean elementTypeMatches(final Type elementType, final Class<?> mapperClass) {
    return elementType instanceof Class<?> cls && cls.equals(mapperClass);
  }

  /**
   * Recover the {@link MappingInternals} view of a {@link Mapping} row — the {@code
   * SerializedLambda}-derived declaring classes and method names that key overrides by {@code
   * (sourceClass, targetClass)} pair. The cast is safe because the three permitted {@link Mapping}
   * record impls ({@link SameTypedTo}, {@link TypedTransformTo}, {@link Via}) all implement {@link
   * MappingInternals} — same sealed permit list on both interfaces.
   */
  private static MappingInternals<?, ?> internalsOf(final Mapping<?, ?> row) {
    return (MappingInternals<?, ?>) row;
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
    final Iso<S, Map<String, Object>> srcReader = srcRefl.structuralIso(source).reverse();
    final Iso<Map<String, Object>, T> tgtBuilder = tgtRefl.structuralIso(target);
    final Iso<Map<String, Object>, Map<String, Object>> remap = remapIso(byTargetName, bySourceName);
    return nullable(srcReader.then(remap).then(tgtBuilder));
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
  private static Iso<?, ?> lazyCacheIso(final Map<TypePair, Iso<?, ?>> cache, final TypePair key) {
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
  private static final Iso<Object, Object> NULLING_ISO = Iso.of(_ -> null, _ -> null);

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
  private record ContainerShape(Kind kind, Type elementType, Class<?> keyClass) {
    enum Kind {
      LIST,
      SET,
      MAP_VALUES,
      OPTIONAL,
    }

    static ContainerShape of(final Type t) {
      if (!(t instanceof ParameterizedType pt)) return null;
      if (!(pt.getRawType() instanceof Class<?> raw)) return null;
      if (raw == List.class) return new ContainerShape(Kind.LIST, pt.getActualTypeArguments()[0], null);
      if (raw == Set.class) return new ContainerShape(Kind.SET, pt.getActualTypeArguments()[0], null);
      if (raw == Optional.class) return new ContainerShape(Kind.OPTIONAL, pt.getActualTypeArguments()[0], null);
      if (raw == Map.class) {
        final var keyArg = pt.getActualTypeArguments()[0];
        if (!(keyArg instanceof Class<?> keyCls)) return null;
        return new ContainerShape(Kind.MAP_VALUES, pt.getActualTypeArguments()[1], keyCls);
      }
      return null;
    }
  }
}
