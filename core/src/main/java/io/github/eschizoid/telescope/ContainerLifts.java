package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.MhIso;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
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
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.function.Supplier;

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
    // A comparator is a problem only for the side being built. Carrying one across a conversion
    // would mean ordering the new element type with an ordering written for the old one, which
    // cannot be done -- but a target that keeps no order has nothing to carry, and refusing there
    // refuses a conversion that would have worked. So each direction asks about its own output.
    final boolean converts = elementIso != Iso.<Object>identity();
    final boolean buildingSortedTarget = set && converts && keepsOrder(tgtRaw);
    final boolean buildingSortedSource = set && converts && keepsOrder(srcRaw);
    final boolean sortedEitherWay = set && (keepsOrder(tgtRaw) || keepsOrder(srcRaw));
    final boolean finish = copyOnWrite(srcRaw) || copyOnWrite(tgtRaw);
    if (!buildingSortedTarget && !buildingSortedSource && !sortedEitherWay && !finish) return loop;
    return Iso.of(
      src -> {
        if (buildingSortedTarget) refuseCarriedComparator(src);
        return finishCollection(orderable(() -> loop.to(src), tgtRaw), tgtRaw);
      },
      tgt -> {
        if (buildingSortedSource) refuseCarriedComparator(tgt);
        return finishCollection(orderable(() -> loop.from(tgt), srcRaw), srcRaw);
      }
    );
  }

  /** Whether a declared raw type keeps its elements in an order, and so needs them comparable. */
  private static boolean keepsOrder(final Class<?> raw) {
    return SortedSet.class.isAssignableFrom(raw);
  }

  private static void refuseCarriedComparator(final Object input) {
    if (input instanceof SortedSet<?> sorted && sorted.comparator() != null) {
      throw new IllegalStateException(
        "Deep map: a custom sorted-set comparator cannot be reused with changed " +
          "element types. Supply an explicit Mapping.via(...) row with a target comparator."
      );
    }
  }

  /**
   * Builds the container, turning the cast a sorted one raises on its first insert into a refusal
   * that says whose element type is not orderable and what to do about it.
   *
   * <p>The bare cast names the element class and {@code Comparable} and nothing else — not the
   * field, not the container, not the library. It also never happens for an empty source, since
   * nothing is inserted, so the shape reads as working until a row arrives with something in it.
   */
  private static Object orderable(final Supplier<Object> build, final Class<?> outRaw) {
    if (!keepsOrder(outRaw)) return build.get();
    try {
      return build.get();
    } catch (final ClassCastException e) {
      throw new IllegalStateException(
        "Deep map: " +
          outRaw.getName() +
          " keeps its elements in order, and the converted element type does not implement" +
          " Comparable. Give the target an explicit comparator through a Mapping.via(...)" +
          " row, or declare it as a set that keeps no order.",
        e
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
   * {@link LinkedHashMap} when the raw class is the {@link Map} interface itself (see {@link
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

  /**
   * The intermediate allocator for {@code raw}, or {@code null} when there is not one. Probing it
   * means calling it, so a class whose constructor throws fails here — which is where it should
   * fail, while the plan is being built, rather than once per conversion afterwards.
   *
   * <p>The call cannot fail by being impossible, only by throwing. Of the two ways the allocator
   * builds a supplier, one binds a constructor handle and refuses an abstract type before doing so,
   * and the other binds {@code builder()} and {@code build()}, which are ordinary methods. So no
   * supplier it returns can raise a linkage error for having nothing to instantiate, which is what
   * a catch here used to guard against.
   */
  private static Supplier<Object> probeAllocator(final Class<?> raw) {
    final var alloc = Beans.intermediateAllocator(raw);
    return alloc.get() == null ? null : alloc;
  }

  /**
   * The last two questions an allocator asks before giving up, shared by all three families.
   *
   * <p>A declared type that cannot be instantiated at all — an interface, an abstract class — is
   * asking for whatever implements its contract, so the family's default stands in. That is what
   * the generated path does for the same declaration, and a type it allocates and this one refuses
   * is a program that compiles under {@code @Bridge} and throws under {@code mapper(...)}.
   *
   * <p>A concrete one gets a public-lookup constructor handle. The tables below exist because
   * {@code privateLookupIn} refuses {@code java.base}, which {@code publicLookup} does not need: it
   * binds a public no-argument constructor on any exported class. Under native image such a
   * constructor needs reachability metadata, where a hard-coded allocator needs none — so the table
   * keeps the common shapes direct and only the tail comes through here.
   *
   * @return an allocator, or {@code null} when neither question has an answer
   */
  private static Function<Object, Object> fallbackAllocatorFor(
    final Class<?> raw,
    final Class<?> defaultImpl,
    final Function<Object, Object> defaultAlloc
  ) {
    if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
      // Only where the default is one of them. A declared type the default does not implement
      // cannot hold it, so allocating one moves the failure from plan time to the first conversion
      // and turns a diagnostic naming the type into a bare cast error. The generated path refuses
      // that pairing outright, so refusing is what keeps the two in step.
      //
      // The allocator handed in is the family's sized one, so a type reaching this branch is
      // allocated exactly as a type the table names would be.
      return raw.isAssignableFrom(defaultImpl) ? defaultAlloc : null;
    }
    try {
      final var ctor = MethodHandles.publicLookup().findConstructor(raw, MethodType.methodType(void.class));
      return ignored -> {
        try {
          return ctor.invoke();
        } catch (final Throwable t) {
          throw new IllegalStateException("Deep map: " + raw.getName() + " refused its no-argument constructor", t);
        }
      };
    } catch (final NoSuchMethodException | IllegalAccessException e) {
      return null;
      // A missing-registration Error under exact reachability metadata is deliberately not caught:
      // it names the class the image was built without, which is more useful to an adopter than
      // this method's fallthrough would be.
    }
  }

  // JDK collection classes live in java.base — `Beans.intermediateAllocator` can't bind them
  // via LambdaMetafactory's privateLookupIn (java.base doesn't grant private lookup to app code).
  // Hard-code the common JDK Collection / Map raws so the standard shapes are allocated by a
  // direct `new`, needing no lookup and no reachability metadata. A declared type the table does
  // not name goes to `probeAllocator`, whose lookup does reach a user-defined subclass via the
  // user's own package, and then to `fallbackAllocatorFor` for the tail.
  private static Function<Object, Object> listAllocatorFor(final Class<?> raw) {
    if (raw == List.class || raw == Collection.class || raw == ArrayList.class) return input ->
      new ArrayList<>(((Collection<?>) input).size());
    if (raw == LinkedList.class) return ignored -> new LinkedList<>();
    // The next three never reach this method: containerViewOf yields LIST only for List subtypes,
    // so a Queue- or Deque-typed component either pairs through the raw same-kind copy path (which
    // allocates via Beans.intermediateAllocator) or is rejected outright when its element types
    // differ. They stay as a safety net for a future pairing rule, unsized because nothing can
    // exercise the sizing. Vector and Stack below are List subtypes and are fully reachable.
    if (raw == ArrayDeque.class) return ignored -> new ArrayDeque<>();
    if (raw == Vector.class) return input -> new Vector<>(((Collection<?>) input).size());
    if (raw == Stack.class) return ignored -> new Stack<>();
    // PriorityQueue rejects a zero initial capacity outright, so a size-derived argument would
    // throw on an empty source.
    if (raw == PriorityQueue.class) return ignored -> new PriorityQueue<>();
    // LinkedBlockingQueue's int argument is a hard capacity bound rather than a sizing hint, so a
    // size-derived value would make the rebuilt queue reject every later offer.
    if (raw == LinkedBlockingQueue.class) return ignored -> new LinkedBlockingQueue<>();
    if (raw == CopyOnWriteArrayList.class) return input -> {
      final int size = ((Collection<?>) input).size();
      return size <= 1 ? new CopyOnWriteArrayList<>() : new ArrayList<>(size);
    };
    final var alloc = probeAllocator(raw);
    if (alloc != null) return ignored -> alloc.get();
    final var fallback = fallbackAllocatorFor(raw, ArrayList.class, input ->
      new ArrayList<>(((Collection<?>) input).size())
    );
    if (fallback != null) return fallback;
    // Nothing can make one of these. Falling back to ArrayList would silently write the wrong
    // runtime class into the target field and CCE at the setter, so this throws at plan time with
    // a precise diagnostic instead.
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
    if (raw == TreeSet.class || raw == SortedSet.class || raw == NavigableSet.class) return input ->
      new TreeSet<>(setComparator(input));
    if (raw == ConcurrentSkipListSet.class) return input -> new ConcurrentSkipListSet<>(setComparator(input));
    if (raw == CopyOnWriteArraySet.class) return input -> {
      final int size = ((Collection<?>) input).size();
      return size <= 1 ? new CopyOnWriteArraySet<>() : new ArrayList<>(size);
    };
    final var alloc = probeAllocator(raw);
    if (alloc != null) return ignored -> alloc.get();
    final var fallback = fallbackAllocatorFor(raw, LinkedHashSet.class, input ->
      LinkedHashSet.newLinkedHashSet(((Collection<?>) input).size())
    );
    if (fallback != null) return fallback;
    throw new IllegalStateException(
      "Deep map: no allocator for Set subtype " +
        raw.getName() +
        ". Add it to setAllocatorFor (java.base classes can't bind via LambdaMetafactory's " +
        "privateLookupIn) or supply an explicit `Mapping.via(...)` row."
    );
  }

  /**
   * Map-side allocator. A bare {@code Map} rebuilds as a {@code LinkedHashMap}, so an ordered
   * source behind an interface-typed field keeps its iteration order across the conversion, and the
   * Map side matches the Set side, which has always rebuilt as a {@code LinkedHashSet}. A field
   * declared as {@code HashMap} asked for that class specifically and still gets it.
   *
   * <p>{@code IdentityHashMap} and {@code WeakHashMap} are accepted but carry different semantics
   * from a plain {@code HashMap} ({@code IdentityHashMap} uses reference equality for keys, {@code
   * WeakHashMap} GCs keys without strong references) — adopters needing preservation declare an
   * explicit {@code Mapping.via(...)} row. {@code EnumMap} is rejected at plan-time because its
   * no-arg constructor doesn't exist (it needs the {@code Class<K>} arg); adopters must use the
   * codegen path or an explicit row.
   */
  private static Function<Object, Object> mapAllocatorFor(final Class<?> raw) {
    if (raw == HashMap.class) return input -> HashMap.newHashMap(((Map<?, ?>) input).size());
    if (raw == Map.class || raw == LinkedHashMap.class) return input ->
      LinkedHashMap.newLinkedHashMap(((Map<?, ?>) input).size());
    if (raw == TreeMap.class || raw == SortedMap.class || raw == NavigableMap.class) return input ->
      new TreeMap<>(mapComparator(input));
    if (raw == ConcurrentHashMap.class || raw == ConcurrentMap.class) return input ->
      new ConcurrentHashMap<>(((Map<?, ?>) input).size());
    if (raw == ConcurrentSkipListMap.class) return input -> new ConcurrentSkipListMap<>(mapComparator(input));
    if (raw == IdentityHashMap.class) return input -> new IdentityHashMap<>(((Map<?, ?>) input).size());
    // WeakHashMap ships no newWeakHashMap factory and its int argument is table capacity,
    // so the element count has to be divided by the 0.75 load factor to size a table that
    // holds them without a resize.
    if (raw == WeakHashMap.class) return input -> new WeakHashMap<>(capacityFor(((Map<?, ?>) input).size()));
    if (raw == EnumMap.class) throw new IllegalStateException(
      "Deep map: EnumMap targets are not supported via auto-Iso lift — EnumMap has no no-arg " +
        "constructor (it needs the Class<K> key class). Use the codegen path or supply an " +
        "explicit `Mapping.via(...)` row that constructs the EnumMap with its key class."
    );
    final var alloc = probeAllocator(raw);
    if (alloc != null) return ignored -> alloc.get();
    final var fallback = fallbackAllocatorFor(raw, LinkedHashMap.class, input ->
      LinkedHashMap.newLinkedHashMap(((Map<?, ?>) input).size())
    );
    if (fallback != null) return fallback;
    throw new IllegalStateException(
      "Deep map: no allocator for Map subtype " +
        raw.getName() +
        ". Add it to mapAllocatorFor (java.base classes can't bind via LambdaMetafactory's " +
        "privateLookupIn) or supply an explicit `Mapping.via(...)` row."
    );
  }

  /**
   * Table capacity that holds {@code size} entries without a resize, for the hash containers whose
   * int constructor takes a table capacity and that ship no {@code newXxx} sizing factory. The
   * JDK's own factories apply the same division; this exists for the types that lack one.
   *
   * <p>A table built straight from an element count only resizes when that count exceeds {@code
   * 0.75 * nextPowerOfTwo(count)} — the top quarter of each power-of-two band, so roughly half of
   * all sizes are unaffected. When it does fire it costs one reallocation plus a rehash of
   * everything already inserted, and the final table is the same size either way: this trades no
   * memory for removing that resize.
   */
  static int capacityFor(final int size) {
    return (int) Math.ceil(size / 0.75d);
  }

  @SuppressWarnings("unchecked")
  private static Comparator<Object> mapComparator(final Object input) {
    return input instanceof SortedMap<?, ?> sorted ? (Comparator<Object>) sorted.comparator() : null;
  }

  @SuppressWarnings("unchecked")
  private static Comparator<Object> setComparator(final Object input) {
    return input instanceof SortedSet<?> sorted ? (Comparator<Object>) sorted.comparator() : null;
  }
}
