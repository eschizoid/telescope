package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Engine for {@link Telescope#map(Class, Class, Mapping[])} / {@link Telescope#mapper(Class, Class,
 * Mapping[])}. Walks the source/target structure pair-by-pair and caches an {@link Iso} per {@code
 * (sourceClass, targetClass)} pair encountered — same-named scalar components identity-link via
 * {@link Iso#identity()}, nested records/beans recurse, {@code List<X>↔List<Y>} / {@code Map<K,
 * X>↔Map<K, Y>} / {@code Optional<X>↔Optional<Y>} lift the inner element {@code Iso} through the
 * container via {@link Iso#liftList}, {@link Iso#liftOptional}, {@link Iso#liftMapValues}.
 *
 * <p><b>Lattice-first.</b> The cache value is {@link Iso} directly — the lattice primitive. No
 * intermediate Object-typed plumbing, no parallel link tables. Per-record assembly composes
 * per-component Isos through {@link Reflective#construct} on each side.
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
  public static <A, B> Iso<A, B> resolve(
    final Class<A> source,
    final Class<B> target,
    final Mapping<?, ?>[] overrides
  ) {
    return resolution(source, target, overrides).iso;
  }

  public static <A, B> Mapper<A, B> resolveMapper(
    final Class<A> source,
    final Class<B> target,
    final Mapping<?, ?>[] overrides
  ) {
    final var r = resolution(source, target, overrides);
    return new Mapper<>(r.iso, source, target, r.patchTable);
  }

  // ---------- Resolution (shared by both public entries) ----------

  @SuppressWarnings("unchecked")
  private static <A, B> Resolution<A, B> resolution(
    final Class<A> source,
    final Class<B> target,
    final Mapping<?, ?>[] overrides
  ) {
    final var overrideTable = groupOverridesByPair(overrides);
    final var cache = new HashMap<TypePair, Iso<?, ?>>();
    final var topSteps = new LinkedHashMap<String, FieldStep>();
    populateIso(source, target, overrideTable, cache, topSteps);
    final var iso = (Iso<A, B>) Objects.requireNonNull(cache.get(new TypePair(source, target)));
    final var patchTable = new LinkedHashMap<String, Mapper.PatchEntry>();
    topSteps.forEach((tgtName, step) ->
      patchTable.put(tgtName, new Mapper.PatchEntry(step.sourceName, v -> ((Iso<Object, Object>) step.iso).from(v)))
    );
    return new Resolution<>(iso, patchTable);
  }

  // ---------- Override grouping ----------

  private static Map<TypePair, List<Mapping<?, ?>>> groupOverridesByPair(final Mapping<?, ?>[] overrides) {
    final var grouped = new HashMap<TypePair, List<Mapping<?, ?>>>();
    for (final var row : overrides) {
      grouped.computeIfAbsent(new TypePair(row.sourceClass(), row.targetClass()), k -> new ArrayList<>()).add(row);
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
    final Map<TypePair, Iso<?, ?>> cache,
    final Map<String, FieldStep> topStepsOut
  ) {
    final var key = new TypePair(source, target);
    if (cache.containsKey(key)) return;
    cache.put(key, null); // reserve slot so cycle-re-entry short-circuits

    final var srcRefl = Reflective.of(source);
    final var tgtRefl = Reflective.of(target);

    final var byTargetName = new LinkedHashMap<String, FieldStep>();
    final var bySourceName = new LinkedHashMap<String, FieldStep>();
    final var claimedTgt = new HashSet<String>();
    final var claimedSrc = new HashSet<String>();

    for (final var row : overrides.getOrDefault(key, List.of())) {
      // Normalize raw method names per side — record::name stays "name", bean::getName becomes
      // "name".
      final var srcField = srcRefl.normalize(row.sourceField());
      final var tgtField = tgtRefl.normalize(row.targetField());
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
        autoIso(srcRefl.genericType(source, name), tgtRefl.genericType(target, name), name, overrides, cache)
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
    final Map<TypePair, Iso<?, ?>> cache
  ) {
    // (a) Same generic type → identity Iso.
    if (srcType.equals(tgtType)) return Iso.identity();

    // (b) Both reflectable (record or bean) → recurse, return cache-reading Iso so cycles work.
    if (srcType instanceof Class<?> srcCls && tgtType instanceof Class<?> tgtCls) {
      if (isReflectable(srcCls) && isReflectable(tgtCls)) {
        populateIso(srcCls, tgtCls, overrides, cache, null);
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
        cache
      );
      return switch (srcShape.kind) {
        case LIST -> Iso.liftList(eraseIso(elementIso));
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
    // Common scalars that should NOT be treated as reflectable beans.
    if (cls == String.class || cls == CharSequence.class) return false;
    if (Number.class.isAssignableFrom(cls)) return false;
    if (cls == Boolean.class || cls == Character.class) return false;
    return true;
  }

  // ---------- Iso assembly ----------

  /**
   * Build the per-pair {@code Iso<S, T>} by walking each side's "construct" through its {@link
   * Reflective} and running the per-component Iso forward (for {@code S → T}) or backward (for
   * {@code T → S}). The step tables are name-keyed so this works regardless of component
   * declaration order.
   */
  @SuppressWarnings("unchecked")
  private static <S, T> Iso<S, T> assembleIso(
    final Class<S> source,
    final Class<T> target,
    final Reflective srcRefl,
    final Reflective tgtRefl,
    final Map<String, FieldStep> byTargetName,
    final Map<String, FieldStep> bySourceName
  ) {
    return Iso.of(
      s -> {
        if (s == null) return null;
        return (T) tgtRefl.construct(target, tName -> {
          final var step = byTargetName.get(tName);
          return ((Iso<Object, Object>) step.iso).to(srcRefl.read(s, step.sourceName));
        });
      },
      t -> {
        if (t == null) return null;
        return (S) srcRefl.construct(source, sName -> {
          final var step = bySourceName.get(sName);
          return ((Iso<Object, Object>) step.iso).from(tgtRefl.read(t, step.targetName));
        });
      }
    );
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
   * Bundled return from {@link #resolution(Class, Class, Mapping[])} — the Iso and the patch table.
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
      MAP_VALUES,
      OPTIONAL,
    }

    static ContainerShape of(final Type t) {
      if (!(t instanceof ParameterizedType pt)) return null;
      if (!(pt.getRawType() instanceof Class<?> raw)) return null;
      if (raw == List.class) return new ContainerShape(Kind.LIST, pt.getActualTypeArguments()[0], null);
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
