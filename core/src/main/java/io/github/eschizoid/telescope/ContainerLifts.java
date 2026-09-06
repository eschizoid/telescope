package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.MhIso;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * Container-shape lifting for {@link DeepMap}: element-copy Isos for raw same-kind container
 * subtype pairs, element-wise {@code List} / {@code Set} / {@code Map}-values lifts that allocate
 * the target's concrete raw class, and the per-kind allocator tables backing them. JDK collection
 * classes live in {@code java.base} — {@link Beans#intermediateAllocator} can't bind them via
 * LambdaMetafactory's {@code privateLookupIn} — so the common JDK raws are hard-coded per kind,
 * with {@code intermediateAllocator} as the fallback for user-defined subclasses (where LMF DOES
 * work via the user's own package). Each lift consults {@link MhIso} first so a composed-handle
 * leaf element iterates via a dedicated MethodHandle loop rather than a megamorphic Java-loop
 * lambda.
 */
final class ContainerLifts {

  private ContainerLifts() {}

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
  static Iso<?, ?> collectionCopyIso(final Class<?> srcCls, final Class<?> tgtCls) {
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
  static Iso<?, ?> mapCopyIso(final Class<?> srcCls, final Class<?> tgtCls) {
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

  /**
   * List-level lift that writes into the target's concrete raw class. Element-wise forward /
   * backward via the {@code elementIso}, allocating fresh source and target instances via {@link
   * Beans#intermediateAllocator}. A {@code List<X> ↔ ArrayList<Y>} pair, or an {@code ArrayList<X>
   * ↔ LinkedList<Y>} pair, produces a result whose runtime class matches the declared target raw
   * class. Falls back to {@link ArrayList} for the raw {@link List} / {@link Collection} interface,
   * where there's no concrete class to allocate.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  static Iso<?, ?> liftListIntoTargetRaw(
    final Iso<Object, Object> elementIso,
    final Class<?> srcRaw,
    final Class<?> tgtRaw
  ) {
    return liftCollectionIntoTargetRaw(elementIso, srcRaw, tgtRaw, false);
  }

  /** Set counterpart; custom sorted comparators cannot safely consume a changed element type. */
  static Iso<?, ?> liftSetIntoTargetRaw(
    final Iso<Object, Object> elementIso,
    final Class<?> srcRaw,
    final Class<?> tgtRaw
  ) {
    return liftCollectionIntoTargetRaw(elementIso, srcRaw, tgtRaw, true);
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> liftCollectionIntoTargetRaw(
    final Iso<Object, Object> elementIso,
    final Class<?> srcRaw,
    final Class<?> tgtRaw,
    final boolean set
  ) {
    final var srcAlloc = set ? setAllocatorFor(srcRaw) : listAllocatorFor(srcRaw);
    final var tgtAlloc = set ? setAllocatorFor(tgtRaw) : listAllocatorFor(tgtRaw);
    final var mh = MhIso.liftCollection(elementIso, srcAlloc, tgtAlloc);
    final Iso<Object, Object> loop =
      mh != null
        ? mh
        : Iso.of(
            src -> {
              if (src == null) return null;
              final var fresh = (Collection) tgtAlloc.apply(src);
              for (final var x : (Collection<?>) src) fresh.add(elementIso.to(x));
              return fresh;
            },
            tgt -> {
              if (tgt == null) return null;
              final var fresh = (Collection) srcAlloc.apply(tgt);
              for (final var x : (Collection<?>) tgt) fresh.add(elementIso.from(x));
              return fresh;
            }
          );
    final boolean checkComparator = set && elementIso != Iso.<Object>identity();
    final boolean finish = copyOnWrite(srcRaw) || copyOnWrite(tgtRaw);
    if (!checkComparator && !finish) return loop;
    return Iso.of(
      src -> {
        if (checkComparator) checkComparator(src);
        return finishCollection(loop.to(src), tgtRaw);
      },
      tgt -> {
        if (checkComparator) checkComparator(tgt);
        return finishCollection(loop.from(tgt), srcRaw);
      }
    );
  }

  private static void checkComparator(final Object input) {
    if (input instanceof SortedSet<?> sorted && sorted.comparator() != null) {
      throw new IllegalStateException(
        "Deep map: a custom sorted-set comparator cannot be reused with changed " +
          "element types. Supply an explicit Mapping.via(...) row with a target comparator."
      );
    }
  }

  private static boolean copyOnWrite(final Class<?> raw) {
    return raw == CopyOnWriteArrayList.class || raw == CopyOnWriteArraySet.class;
  }

  private static Object finishCollection(final Object result, final Class<?> raw) {
    if (result == null) return null;
    if (
      raw == CopyOnWriteArrayList.class && !(result instanceof CopyOnWriteArrayList<?>)
    ) return new CopyOnWriteArrayList<>((Collection<?>) result);
    if (
      raw == CopyOnWriteArraySet.class && !(result instanceof CopyOnWriteArraySet<?>)
    ) return new CopyOnWriteArraySet<>((Collection<?>) result);
    return result;
  }

  /**
   * Map-level lift that writes into the target's concrete raw class. Mirror of {@link
   * #liftListIntoTargetRaw} for Maps. Preserves source keys verbatim (matches {@link
   * Iso#liftMapValues}); the calling site already ensured the key classes match. Falls back to
   * {@link HashMap} when the raw class is the {@link Map} interface itself (see {@link
   * #mapAllocatorFor}).
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  static Iso<?, ?> liftMapIntoTargetRaw(
    final Iso<Object, Object> elementIso,
    final Class<?> srcRaw,
    final Class<?> tgtRaw
  ) {
    final var srcAlloc = mapAllocatorFor(srcRaw);
    final var tgtAlloc = mapAllocatorFor(tgtRaw);
    // MethodHandle entry-loop over the value element's raw handle when it is a composed-handle
    // leaf;
    // keys pass through verbatim. Null value Iso => keep the Java loop.
    final var mh = MhIso.liftMap(elementIso, srcAlloc, tgtAlloc);
    if (mh != null) return mh;
    return Iso.of(
      src -> {
        if (src == null) return null;
        final var fresh = (Map) tgtAlloc.apply(src);
        for (final var e : ((Map<?, ?>) src).entrySet()) fresh.put(e.getKey(), elementIso.to(e.getValue()));
        return fresh;
      },
      tgt -> {
        if (tgt == null) return null;
        final var fresh = (Map) srcAlloc.apply(tgt);
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
  private static Function<Object, Object> listAllocatorFor(final Class<?> raw) {
    if (raw == List.class || raw == Collection.class || raw == ArrayList.class) return input ->
      new ArrayList<>(((Collection<?>) input).size());
    if (raw == LinkedList.class) return ignored -> new LinkedList<>();
    if (raw == ArrayDeque.class) return ignored -> new ArrayDeque<>();
    if (raw == Vector.class) return ignored -> new Vector<>();
    if (raw == Stack.class) return ignored -> new Stack<>();
    if (raw == PriorityQueue.class) return ignored -> new PriorityQueue<>();
    if (raw == LinkedBlockingQueue.class) return ignored -> new LinkedBlockingQueue<>();
    if (raw == CopyOnWriteArrayList.class) return input -> {
      final int size = ((Collection<?>) input).size();
      return size <= 1 ? new CopyOnWriteArrayList<>() : new ArrayList<>(size);
    };
    final var alloc = Beans.intermediateAllocator(raw);
    if (alloc.get() != null) return ignored -> alloc.get();
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

  private static Function<Object, Object> setAllocatorFor(final Class<?> raw) {
    if (raw == Set.class || raw == LinkedHashSet.class) return input ->
      LinkedHashSet.newLinkedHashSet(((Collection<?>) input).size());
    if (raw == HashSet.class) return input -> HashSet.newHashSet(((Collection<?>) input).size());
    if (raw == TreeSet.class) return input -> new TreeSet<>(setComparator(input));
    if (raw == ConcurrentSkipListSet.class) return input -> new ConcurrentSkipListSet<>(setComparator(input));
    if (raw == CopyOnWriteArraySet.class) return input -> {
      final int size = ((Collection<?>) input).size();
      return size <= 1 ? new CopyOnWriteArraySet<>() : new ArrayList<>(size);
    };
    final var alloc = Beans.intermediateAllocator(raw);
    if (alloc.get() != null) return ignored -> alloc.get();
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
  private static Function<Object, Object> mapAllocatorFor(final Class<?> raw) {
    if (raw == Map.class || raw == HashMap.class) return input -> HashMap.newHashMap(((Map<?, ?>) input).size());
    if (raw == LinkedHashMap.class) return input -> LinkedHashMap.newLinkedHashMap(((Map<?, ?>) input).size());
    if (raw == TreeMap.class) return input -> new TreeMap<>(mapComparator(input));
    if (raw == ConcurrentHashMap.class) return input -> new ConcurrentHashMap<>(((Map<?, ?>) input).size());
    if (raw == ConcurrentSkipListMap.class) return input -> new ConcurrentSkipListMap<>(mapComparator(input));
    if (raw == IdentityHashMap.class) return input -> new IdentityHashMap<>(((Map<?, ?>) input).size());
    if (raw == WeakHashMap.class) return ignored -> new WeakHashMap<>();
    if (raw == EnumMap.class) throw new IllegalStateException(
      "Deep map: EnumMap targets are not supported via auto-Iso lift — EnumMap has no no-arg " +
        "constructor (it needs the Class<K> key class). Use the codegen path or supply an " +
        "explicit `Mapping.via(...)` row that constructs the EnumMap with its key class."
    );
    final var alloc = Beans.intermediateAllocator(raw);
    if (alloc.get() != null) return ignored -> alloc.get();
    throw new IllegalStateException(
      "Deep map: no allocator for Map subtype " +
        raw.getName() +
        ". Add it to mapAllocatorFor (java.base classes can't bind via LambdaMetafactory's " +
        "privateLookupIn) or supply an explicit `Mapping.via(...)` row."
    );
  }

  @SuppressWarnings("unchecked")
  private static java.util.Comparator<Object> mapComparator(final Object input) {
    return input instanceof SortedMap<?, ?> sorted ? (java.util.Comparator<Object>) sorted.comparator() : null;
  }

  @SuppressWarnings("unchecked")
  private static java.util.Comparator<Object> setComparator(final Object input) {
    return input instanceof SortedSet<?> sorted ? (java.util.Comparator<Object>) sorted.comparator() : null;
  }
}
