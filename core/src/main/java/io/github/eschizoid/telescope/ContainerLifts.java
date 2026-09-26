package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.MhIso;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.PriorityQueue;
import java.util.Queue;
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
    // A sorted output whose elements change type has to see each converted element before it is
    // inserted, and the fused MethodHandle loop offers nowhere to stand between the two. Asking
    // outside the loop instead would mean converting the first element twice, once to test it and
    // once to insert it -- which is a repeated read, not only a repeated cost, since a conversion
    // that counts or generates would see element zero twice and every other element once. The loop
    // converts once and tests what it is about to insert, so the fusion is what gives way.
    final boolean converts = elementIso != Iso.<Object>identity();
    // Only the side being built has an ordering to establish, so only that side gives up its fused
    // loop. Asking the pair instead would cost the other direction its fusion to buy nothing: the
    // forward half of a sorted-source-to-unsorted-target conversion inserts into a container that
    // orders nothing and can raise no cast for the refusal to describe.
    final var mh = MhIso.liftCollection(elementIso, srcAlloc, tgtAlloc);
    final boolean loopForward = set && converts && keepsOrder(tgtRaw);
    final boolean loopBackward = set && converts && keepsOrder(srcRaw);
    final Iso<Object, Object> loop =
      mh != null && !loopForward && !loopBackward
        ? mh
        : Iso.of(
            src -> mh != null && !loopForward ? mh.to(src) : buildConverted(src, tgtAlloc, elementIso::to, tgtRaw),
            tgt -> mh != null && !loopBackward ? mh.from(tgt) : buildConverted(tgt, srcAlloc, elementIso::from, srcRaw)
          );
    // A comparator is a problem only for the side being built. Carrying one across a conversion
    // would mean ordering the new element type with an ordering written for the old one, which
    // cannot be done -- but a target that keeps no order has nothing to carry, and refusing there
    // refuses a conversion that would have worked. So each direction asks about its own output.
    //
    // The guard can only fire where the input it reads is itself sorted, which for one direction it
    // need not be: building a sorted container out of an unsorted one leaves nothing to ask about,
    // and the result takes natural ordering. That is a silent reordering, and it is what the
    // generated path does too -- one decision on both, rather than two that differ.
    final boolean buildingSortedTarget = set && converts && keepsOrder(tgtRaw);
    final boolean buildingSortedSource = set && converts && keepsOrder(srcRaw);
    // Either of the two above implies this, so it alone decides whether the wrapper is needed.
    final boolean sortedEitherWay = set && (keepsOrder(tgtRaw) || keepsOrder(srcRaw));
    final boolean finish = copyOnWrite(srcRaw) || copyOnWrite(tgtRaw);
    if (!sortedEitherWay && !finish) return loop;
    return Iso.of(
      src -> {
        if (buildingSortedTarget) refuseCarriedComparator(src);
        return finishCollection(loop.to(src), tgtRaw);
      },
      tgt -> {
        if (buildingSortedSource) refuseCarriedComparator(tgt);
        return finishCollection(loop.from(tgt), srcRaw);
      }
    );
  }

  /**
   * Whether a declared raw type keeps its elements in an order, and so needs them comparable.
   *
   * <p>Asked of the declaration rather than of the class that ends up allocated, which is the same
   * answer only because a declaration is either instantiated as itself or stood in for by a family
   * default assignable to it. A default that did not implement its declaration would break that,
   * and so would this.
   */
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
   * Builds the converted container, turning the cast a sorted one raises on an insert it cannot
   * order into a refusal that says which element it was and what to do about it.
   *
   * <p>The catch is around the insert alone, which is the whole of the difference from wrapping the
   * build. A cast from an element conversion, or from a bridge handing back the wrong type, is not
   * an ordering problem and never reaches it.
   *
   * <p>It does not follow that everything reaching it is one. A container ordered by a comparator
   * of its own raises from that comparator, and a fault inside an element's own {@code compareTo}
   * raises from there — both land here and are described as an ordering problem. The cause is kept
   * for that reason: the refusal names the likeliest reading, and the cast underneath it names the
   * actual one.
   *
   * <p>What the insert answers that nothing earlier can is whether an element can be ordered by
   * this container at all, which is not what {@code Comparable} says. The answer always comes from
   * the first insert, so a refusal names one element and never a pair. An element ordered against
   * some other type implements it and still fails, and a container of one element fails alone,
   * since the first key is compared with itself.
   *
   * <p>Each element is converted once and the value inserted is the value converted, so a
   * conversion that counts, generates an id or reads a clock sees every element exactly once. That
   * is why a sorted output whose elements change type does not take the fused MethodHandle loop —
   * it leaves nowhere to stand between converting and inserting.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Object buildConverted(
    final Object input,
    final Function<Object, Object> alloc,
    final Function<Object, Object> convert,
    final Class<?> outRaw
  ) {
    if (input == null) return null;
    final var fresh = (Collection) alloc.apply(input);
    final boolean ordered = keepsOrder(outRaw);
    for (final var x : (Collection<?>) input) {
      final var converted = convert.apply(x);
      if (!ordered) {
        fresh.add(converted);
        continue;
      }
      try {
        fresh.add(converted);
      } catch (final ClassCastException e) {
        // A cast from the container's own comparison is an ordering problem. A cast from inside an
        // element's compareTo is the element's, and describing it as an ordering one would send the
        // reader to supply an ordering for a type that already has a working one.
        if (orderedAgainstItsOwnKind(converted)) throw e;
        throw unorderable(converted, outRaw, e);
      }
    }
    return fresh;
  }

  /**
   * The refusal a sorted container's own insert earns, told from the element it rejected.
   *
   * <p>Asked of the cast rather than ahead of it, because what a sorted container needs is not that
   * its elements implement {@code Comparable} but that they can be ordered against each other. An
   * element ordered against some other type satisfies the first and fails the second, and a
   * container of one element fails alone, since the first key is compared with itself.
   *
   * <p>The element is never null here: a sorted container raises {@code NullPointerException} for
   * one, not a cast, so this is only ever reached with something to name.
   *
   * <p>Says where the ordering would come from rather than that one is missing. A target with a
   * comparator of its own reaches this too, through that comparator failing, and telling its author
   * to supply what they already supplied is the reading to avoid.
   */
  private static IllegalStateException unorderable(
    final Object element,
    final Class<?> outRaw,
    final ClassCastException cause
  ) {
    final var implementing =
      element instanceof Comparable
        ? ", though its type implements Comparable"
        : ", and its type does not implement Comparable";
    return new IllegalStateException(
      "Deep map: " +
        outRaw.getName() +
        " keeps its elements in order, and " +
        element.getClass().getName() +
        " could not be ordered there" +
        implementing +
        ". Supply an ordering these elements accept through a Mapping.via(...) row, or" +
        " declare the target as a set that keeps no order. The cause is the cast itself.",
      cause
    );
  }

  /**
   * Whether an element declares itself comparable with things of its own kind.
   *
   * <p>Asked of the declared type argument rather than of a stack trace. An element typed {@code
   * Comparable<SomethingElse>} fails in the synthetic bridge before its own method body runs, and
   * that is an ordering problem worth describing; one typed against its own kind has already said
   * it can be ordered, so a cast escaping it came from its own logic and is not ours to relabel.
   *
   * <p>The declaration is looked for wherever it is, not only on the element's own class: a domain
   * type reaches {@code Comparable} through an interface as often as it declares one directly, and
   * a walk that stopped at superclasses would relabel exactly those elements' own faults.
   *
   * <p>Raw or absent {@code Comparable} answers false, which routes to the ordering refusal. That
   * is the safer direction: the refusal names the element and keeps the cast as its cause, so a
   * wrong guess here costs a sentence rather than the diagnosis. A raw one takes {@code Object} and
   * casts it itself, so its faults are more often its own than not -- it stays with the refusal
   * because the cause is preserved either way, not because the reading is clearly right.
   */
  private static boolean orderedAgainstItsOwnKind(final Object element) {
    // Seeded with the class chain; each class's own interfaces are reached by the walk below, so
    // adding them here as well would only be a second route to the same types. The graph is finite
    // and acyclic, so a diamond costs a repeat visit and nothing more.
    final var pending = new ArrayDeque<Type>();
    for (var c = element.getClass(); c != null; c = c.getSuperclass()) pending.add(c);
    while (!pending.isEmpty()) {
      final var type = pending.poll();
      // Only a parameterized Comparable answers the question. A raw one names no type argument, so
      // it reaches the walk as an ordinary class and is followed like any other.
      if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == Comparable.class) {
        final var against = parameterized.getActualTypeArguments()[0];
        return against instanceof Class<?> cls && cls.isAssignableFrom(element.getClass());
      }
      final var raw = type instanceof ParameterizedType parameterized ? parameterized.getRawType() : type;
      if (raw instanceof Class<?> cls) pending.addAll(List.of(cls.getGenericInterfaces()));
    }
    return false;
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
    // A Deque- or Queue-typed component is viewed as a list, so an ArrayDeque is what satisfies
    // the declaration. Its int argument is an element count rather than a table capacity, so it
    // takes the source's size directly -- unlike the hash families. Only the two interfaces arrive
    // here: a concrete deque is not viewed as a container at all, so the lift never asks about one.
    // A zero needs no guard either:
    // the constructor reads it as one slot, which is the empty case and not an error. That is
    // PriorityQueue's constraint, not this one, and it is handled where it applies below.
    if (raw == Deque.class || raw == Queue.class) return input -> new ArrayDeque<>(((Collection<?>) input).size());
    // Vector and Stack are List subtypes and were always reachable.
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
    if (alloc != null) return orderingAware(raw, SortedSet.class, ContainerLifts::setComparator, ignored ->
      alloc.get()
    );
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
    if (alloc != null) return orderingAware(raw, SortedMap.class, ContainerLifts::mapComparator, ignored ->
      alloc.get()
    );
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

  /**
   * Wraps an allocator so a declared subtype of a sorted container keeps the order its source
   * carried.
   *
   * <p>The JDK's own sorted classes are allocated by name above, with the source's comparator
   * handed to a constructor that takes one. A subtype answers to none of those names, and Java does
   * not inherit constructors, so it can receive a comparator only where it declares a constructor
   * for one. Where it declares one, that constructor is used. Where it does not, a source ordered
   * by a comparator has nowhere to put it, and a rebuild would reorder by the elements' own {@code
   * compareTo} while producing a container of the right type and size — so it fails instead of
   * returning something quietly different. A source ordered naturally loses nothing and is
   * allocated as before.
   */
  private static Function<Object, Object> orderingAware(
    final Class<?> raw,
    final Class<?> sortedIface,
    final Function<Object, Comparator<Object>> comparatorOf,
    final Function<Object, Object> plain
  ) {
    if (!sortedIface.isAssignableFrom(raw)) return plain;
    final MethodHandle ctor;
    try {
      ctor = MethodHandles.publicLookup().findConstructor(raw, MethodType.methodType(void.class, Comparator.class));
    } catch (final NoSuchMethodException | IllegalAccessException e) {
      return input -> {
        if (comparatorOf.apply(input) != null) {
          throw new IllegalStateException(
            "Deep map: " +
              // The canonical name, because the generated path names the same class the same
              // way
              // and an adopter comparing the two messages should not have to translate.
              (raw.getCanonicalName() == null ? raw.getName() : raw.getCanonicalName()) +
              " declares no constructor taking a Comparator, so the source's ordering cannot" +
              " be carried into it. Declare one, declare the field as the interface, or" +
              " supply an explicit Mapping.via(...) row for it."
          );
        }
        return plain.apply(input);
      };
    }
    return input -> {
      final var comparator = comparatorOf.apply(input);
      // Natural ordering is what the no-argument constructor already produces, and a constructor
      // taking a comparator is free to reject a null one.
      if (comparator == null) return plain.apply(input);
      try {
        return ctor.invoke(comparator);
      } catch (final Throwable t) {
        throw new IllegalStateException("Deep map: " + raw.getName() + " refused its Comparator constructor", t);
      }
    };
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
