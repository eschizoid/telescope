package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

  // ---------- Public entries (called from Telescope.map / Telescope.mapper) ----------

  @SuppressWarnings("exports") // Intentional: Iso is module-internal; consumed only by Telescope.
  public static <A, B> Iso<A, B> resolve(final Class<A> source, final Class<B> target, final MapStep[] steps) {
    return resolution(source, target, steps).iso;
  }

  public static <A, B> Mapper<A, B> resolveMapper(final Class<A> source, final Class<B> target, final MapStep[] steps) {
    final var r = resolution(source, target, steps);
    return new Mapper<>(r.iso, source, target, r.patchTable);
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
    // One bean-side Reflective per resolution call: a singleton Reflective.BEANS when no hints
    // exist, otherwise one hint-aware Reflective threaded through every recursion call so the
    // anonymous instance isn't re-allocated per type pair.
    final var beanRefl = hintMap.isEmpty() ? Reflective.BEANS : Reflective.beansWithHints(hintMap);
    final var overrideTable = groupOverridesByPair(overrides.toArray(Mapping<?, ?>[]::new));
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

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Beans.BeanWriter<?> writerFor(final WriteHint<?> hint) {
    // Each *Writer constructor throws IllegalStateException with a writeBean(class, STRATEGY)-
    // shaped message when its prerequisite is missing, so no rewrap is needed — the underlying
    // exception already names the actual API the user called.
    final var cls = (Class) hint.targetClass();
    return switch (hint.strategy()) {
      case BUILDER -> Beans.builderWriter(cls);
      case SETTERS -> Beans.settersWriter(cls);
      case FIELDS -> Beans.fieldsWriter(cls);
      case CONSTRUCTOR -> Beans.constructorWriter(cls, Beans.propertyNames(cls).length);
    };
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

  private static Map<TypePair, List<Mapping<?, ?>>> groupOverridesByPair(final Mapping<?, ?>[] overrides) {
    final var grouped = new HashMap<TypePair, List<Mapping<?, ?>>>();
    for (final var row : overrides) {
      final var internals = internalsOf(row);
      grouped
        .computeIfAbsent(new TypePair(internals.sourceClass(), internals.targetClass()), _ -> new ArrayList<>())
        .add(row);
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

    for (final var row : overrides.getOrDefault(key, List.of())) {
      // Normalize raw method names per side — record::name stays "name", bean::getName becomes
      // "name".
      final var internals = internalsOf(row);
      final var srcField = srcRefl.normalize(internals.sourceField());
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
      final var step = new FieldStep(srcField, tgtField, fieldIsoOf(row));
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

    cache.put(key, assembleIso(source, target, srcRefl, tgtRefl, byTargetName, bySourceName));
    if (topStepsOut != null) topStepsOut.putAll(byTargetName);
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
   */
  private static Iso<?, ?> fieldIsoOf(final Mapping<?, ?> row) {
    return switch (row) {
      case SameTypedTo<?, ?, ?> r -> r.fieldIso();
      case TypedTransformTo<?, ?, ?, ?> r -> r.fieldIso();
      case Via<?, ?, ?, ?> r -> r.fieldIso();
    };
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

  @SuppressWarnings("unchecked")
  private static Iso<?, ?> lazyCacheIso(final Map<TypePair, Iso<?, ?>> cache, final TypePair key) {
    return Iso.of(
      v -> ((Iso<Object, Object>) cache.get(key)).to(v),
      v -> ((Iso<Object, Object>) cache.get(key)).from(v)
    );
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
