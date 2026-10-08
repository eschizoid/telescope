package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.ContainerCopy;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.MhIso;
import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.pairing.Allocation;
import io.github.eschizoid.telescope.internal.pairing.ContainerAllocation;
import io.github.eschizoid.telescope.internal.pairing.ContainerView;
import io.github.eschizoid.telescope.internal.pairing.Ordering;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import io.github.eschizoid.telescope.internal.pairing.ReflectionProps;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
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
import java.util.Objects;
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
import java.util.function.Function;

/**
 * Container-shape lifting for {@link DeepMap}: element-copy Isos for same-kind container pairs
 * written without type arguments whose elements need no conversion, element-wise {@code List} /
 * {@code Set} / {@code Map}-values lifts that allocate the target's concrete raw class, and the
 * allocator renderings backing them. Which class is built, through which call, and whether it can
 * be built at all are the shared allocation rules' decisions; this file renders them. JDK
 * collection classes live in {@code java.base}, where LambdaMetafactory's {@code privateLookupIn}
 * cannot bind them, so the classes those rules name are written out by name, and a class of the
 * adopter's own is bound through its constructor in its own package. Each lift consults {@link
 * MhIso} first so a composed-handle leaf element iterates via a dedicated MethodHandle loop rather
 * than a megamorphic Java-loop lambda.
 */
final class ContainerLifts {

  private ContainerLifts() {}

  /**
   * Collection ↔ Collection element-copy Iso. A pair of one declared type is copied by {@link
   * #unchangedCopyIso}. For any other pair the forward allocates the target collection through
   * {@link #copyAllocator} and {@code addAll}'s the source into it; backward is symmetric. The
   * pairing spec decides a copy only when both sides can be built, so this never answers null and
   * every side it is handed can be allocated.
   *
   * <p>No element-type recursion: a copy is decided only where neither side names an element type
   * the other could refuse, such as a class declaring no type parameters that fixes them ({@code
   * class ImageUrls extends ArrayList<ImageUrl>}), a generic class or interface used raw, or a
   * class fixing every argument to {@code Object}. Users whose element types differ across sides
   * should declare an explicit row.
   */
  static Iso<?, ?> collectionCopyIso(final Type srcType, final Type tgtType) {
    if (PROPS.sameType(srcType, tgtType)) return unchangedCopyIso(srcType);
    return collectionCopyIso((Class<?>) srcType, (Class<?>) tgtType);
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> collectionCopyIso(final Class<?> srcCls, final Class<?> tgtCls) {
    final var kind = Set.class.isAssignableFrom(tgtCls) ? ContainerView.Kind.SET : ContainerView.Kind.LIST;
    final var srcAlloc = copyAllocator(srcCls, kind);
    final var tgtAlloc = copyAllocator(tgtCls, kind);
    return Iso.of(
      src -> buildConverted(src, tgtAlloc, Function.identity(), tgtCls),
      tgt -> buildConverted(tgt, srcAlloc, Function.identity(), srcCls)
    );
  }

  /**
   * The allocation one side of an element copy fills. The pairing spec decides a copy only when
   * both sides can be built, so every side reaching here can be.
   *
   * <p>An interface is built as the default implementation the shared table names for its family,
   * which carries the source's comparator where the default is a sorted one; the spec accepts the
   * copy on exactly that condition, so the two cannot disagree. An abstract class has no default
   * and never reaches here. Any other class is built as the shared allocation rules decide, and the
   * source's order is carried into it where the class keeps one.
   */
  private static Function<Object, Object> copyAllocator(final Class<?> cls, final ContainerView.Kind kind) {
    return cls.isInterface() ? specAllocatorFor(cls, kind) : allocatorFor(cls, cls, kind);
  }

  /** Map ↔ Map element-copy Iso. Mirror of {@link #collectionCopyIso} via {@code putAll}. */
  static Iso<?, ?> mapCopyIso(final Type srcType, final Type tgtType) {
    if (PROPS.sameType(srcType, tgtType)) return unchangedCopyIso(srcType);
    return mapCopyIso((Class<?>) srcType, (Class<?>) tgtType);
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> mapCopyIso(final Class<?> srcCls, final Class<?> tgtCls) {
    final var kind = ContainerView.Kind.MAP_VALUES;
    final var srcAlloc = copyAllocator(srcCls, kind);
    final var tgtAlloc = copyAllocator(tgtCls, kind);
    return Iso.of(
      src -> buildMap(src, tgtAlloc, Function.identity(), tgtCls),
      tgt -> buildMap(tgt, srcAlloc, Function.identity(), srcCls)
    );
  }

  /**
   * The copy a container of one declared type on both sides takes, in both directions: {@link
   * ContainerCopy#of} over the declared raw class, the copy a generated bridge makes too.
   */
  private static Iso<?, ?> unchangedCopyIso(final Type type) {
    final var declared = rawClassOf(type);
    final Function<Object, Object> copy = input -> ContainerCopy.of(input, declared);
    return Iso.of(copy, copy);
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
  static Iso<?, ?> liftListIntoTargetRaw(final Iso<Object, Object> elementIso, final Type srcType, final Type tgtType) {
    return liftCollectionIntoTargetRaw(elementIso, srcType, tgtType, false);
  }

  /** Set counterpart; custom sorted comparators cannot safely consume a changed element type. */
  static Iso<?, ?> liftSetIntoTargetRaw(final Iso<Object, Object> elementIso, final Type srcType, final Type tgtType) {
    return liftCollectionIntoTargetRaw(elementIso, srcType, tgtType, true);
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Iso<?, ?> liftCollectionIntoTargetRaw(
    final Iso<Object, Object> elementIso,
    final Type srcType,
    final Type tgtType,
    final boolean set
  ) {
    final var srcRaw = rawClassOf(srcType);
    final var tgtRaw = rawClassOf(tgtType);
    final var kind = set ? ContainerView.Kind.SET : ContainerView.Kind.LIST;
    // A sorted output whose elements change type has to see each converted element before it is
    // inserted, and the fused MethodHandle loop offers nowhere to stand between the two. Asking
    // outside the loop instead would mean converting the first element twice, once to test it and
    // once to insert it -- which is a repeated read, not only a repeated cost, since a conversion
    // that counts or generates would see element zero twice and every other element once. The loop
    // converts once and tests what it is about to insert, so the fusion is what gives way.
    final boolean converts = elementIso != Iso.<Object>identity();
    final boolean copies = !converts && copiesBothWays(srcRaw, srcType, tgtRaw, tgtType, kind);
    final var srcCopy = copies ? copyConstructorIfRefused(srcRaw, srcType, kind, tgtRaw) : null;
    final var tgtCopy = copies ? copyConstructorIfRefused(tgtRaw, tgtType, kind, srcRaw) : null;
    final var srcAlloc = srcCopy != null ? null : liftAllocatorFor(srcRaw, srcType, kind);
    final var tgtAlloc = tgtCopy != null ? null : liftAllocatorFor(tgtRaw, tgtType, kind);
    // Only the side being built has an ordering to establish, so only that side gives up its fused
    // loop. Asking the pair instead would cost the other direction its fusion to buy nothing: the
    // forward half of a sorted-source-to-unsorted-target conversion inserts into a container that
    // orders nothing and can raise no cast for the refusal to describe.
    final var mh = srcCopy == null && tgtCopy == null ? MhIso.liftCollection(elementIso, srcAlloc, tgtAlloc) : null;
    final boolean loopForward = set && converts && keepsOrder(tgtRaw);
    final boolean loopBackward = set && converts && keepsOrder(srcRaw);
    final Iso<Object, Object> loop =
      mh != null && !loopForward && !loopBackward
        ? mh
        : Iso.of(
            src ->
              tgtCopy != null
                ? copied(src, tgtCopy)
                : mh != null && !loopForward
                  ? mh.to(src)
                  : buildConverted(src, tgtAlloc, elementIso::to, tgtRaw),
            tgt ->
              srcCopy != null
                ? copied(tgt, srcCopy)
                : mh != null && !loopBackward
                  ? mh.from(tgt)
                  : buildConverted(tgt, srcAlloc, elementIso::from, srcRaw)
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
    final var targetRefusal = set && converts ? convertedRefusal(tgtType, tgtRaw) : null;
    final var sourceRefusal = set && converts ? convertedRefusal(srcType, srcRaw) : null;
    // Either refusal implies this, so it alone decides whether the wrapper is needed.
    final boolean sortedEitherWay = set && (keepsOrder(tgtRaw) || keepsOrder(srcRaw));
    final boolean finish = copyOnWrite(srcRaw) || copyOnWrite(tgtRaw);
    if (!sortedEitherWay && !finish) return loop;
    return Iso.of(
      src -> {
        if (targetRefusal != null) refuseCarriedComparator(src, ContainerView.Kind.SET, targetRefusal);
        return finishCollection(loop.to(src), tgtRaw);
      },
      tgt -> {
        if (sourceRefusal != null) refuseCarriedComparator(tgt, ContainerView.Kind.SET, sourceRefusal);
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

  /**
   * What the shared rules refuse a sorted set whose elements are converted, in the words they
   * refuse it with, or null where the set keeps no order to lose. The class asked about is the
   * declared one: a declaration is built as itself or as a family default that keeps an order
   * exactly when it does, and whether there is an order is all this question turns on.
   */
  private static String convertedRefusal(final Type declared, final Class<?> raw) {
    return RULES.orderingFor(declared, raw, ContainerView.Kind.SET, false) instanceof Ordering.Refuse<Type> refuse
      ? refuse.reason()
      : null;
  }

  /**
   * Refuses a source ordered by a comparator, in {@code reason}'s words. A source in natural order
   * has no order the rebuild could lose, and passes.
   */
  private static void refuseCarriedComparator(final Object input, final ContainerView.Kind kind, final String reason) {
    if (comparatorOf(kind, input) != null) throw new IllegalStateException(reason);
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
    final var map = Map.class.isAssignableFrom(outRaw);
    return new IllegalStateException(
      PairingMessages.unorderableInsertHead(outRaw.getName(), map) +
        element.getClass().getName() +
        PairingMessages.unorderableInsertComparable(element instanceof Comparable) +
        PairingMessages.unorderableInsertAdvice(map),
      cause
    );
  }

  /**
   * Puts one entry into a map being built, turning the cast a sorted map raises on a key it cannot
   * order into the refusal {@link #unorderable} describes. A cast from inside a key's own {@code
   * compareTo}, for a key ordered against its own kind, is the key's and propagates as it is.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static void putOrdered(final Map fresh, final Object key, final Object value, final Class<?> outRaw) {
    try {
      fresh.put(key, value);
    } catch (final ClassCastException e) {
      if (orderedAgainstItsOwnKind(key)) throw e;
      throw unorderable(key, outRaw, e);
    }
  }

  /**
   * Builds a map from {@code input}'s entries, each value converted by {@code convert} and each key
   * kept, through {@link #putOrdered} where the map keeps its keys in order.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Object buildMap(
    final Object input,
    final Function<Object, Object> alloc,
    final Function<Object, Object> convert,
    final Class<?> outRaw
  ) {
    if (input == null) return null;
    final var fresh = (Map) alloc.apply(input);
    final boolean ordered = SortedMap.class.isAssignableFrom(outRaw);
    for (final var e : ((Map<?, ?>) input).entrySet()) {
      final var value = convert.apply(e.getValue());
      if (ordered) putOrdered(fresh, e.getKey(), value, outRaw);
      else fresh.put(e.getKey(), value);
    }
    return fresh;
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
   * <p>A type argument that is a type variable is read through the arguments the walk passed on its
   * way up, so {@code final class Sa extends Cmp<Sa>} under {@code abstract class Cmp<T extends
   * Cmp<T>> implements Comparable<T>} is ordered against its own kind. A variable nothing on the
   * way binds answers false. That is where an instance of a generic class lands when the class
   * leaves its own parameter as the argument, because a runtime class carries no type arguments.
   *
   * <p>Raw or absent {@code Comparable} answers false, which routes to the ordering refusal. That
   * is the safer direction: the refusal names the element and keeps the cast as its cause, so a
   * wrong guess here costs a sentence rather than the diagnosis. A raw one takes {@code Object} and
   * casts it itself, so its faults are more often its own than not -- it stays with the refusal
   * because the cause is preserved either way, not because the reading is clearly right.
   *
   * <p>The generated bridge answers the same question at compile time, of the declared element
   * class, with the same walk.
   */
  private static boolean orderedAgainstItsOwnKind(final Object element) {
    final var own = element.getClass();
    // The graph is finite and acyclic, so a diamond costs a repeat visit and nothing more.
    final var pending = new ArrayDeque<Supertype>();
    pending.add(new Supertype(own, Map.of()));
    while (!pending.isEmpty()) {
      final var visit = pending.poll();
      final var type = visit.type();
      final var raw = type instanceof ParameterizedType parameterized ? parameterized.getRawType() : type;
      if (!(raw instanceof Class<?> cls)) continue;
      // Only a parameterized Comparable answers the question. A raw one names no type argument, so
      // it is followed like any other class, and leads nowhere.
      if (cls == Comparable.class && type instanceof ParameterizedType parameterized) {
        final var against = visit.resolve(parameterized.getActualTypeArguments()[0]);
        return against instanceof Class<?> target && target.isAssignableFrom(own);
      }
      final Map<TypeVariable<?>, Type> bindings = new HashMap<>();
      if (type instanceof ParameterizedType parameterized) {
        final var parameters = cls.getTypeParameters();
        final var arguments = parameterized.getActualTypeArguments();
        for (var i = 0; i < parameters.length; i++) bindings.put(parameters[i], visit.resolve(arguments[i]));
      }
      if (cls.getGenericSuperclass() != null) pending.add(new Supertype(cls.getGenericSuperclass(), bindings));
      for (final var iface : cls.getGenericInterfaces()) pending.add(new Supertype(iface, bindings));
    }
    return false;
  }

  /**
   * One step of the walk above: a supertype as its declaring class wrote it, with the values the
   * walk has bound to that class's type variables.
   */
  private record Supertype(Type type, Map<TypeVariable<?>, Type> bindings) {
    /** The type an argument stands for here: a bound variable's value, or the argument as it is. */
    Type resolve(final Type argument) {
      return argument instanceof TypeVariable<?> variable ? bindings.getOrDefault(variable, variable) : argument;
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
   * {@link LinkedHashMap} when the raw class is the {@link Map} interface itself, as the shared
   * allocation table decides (see {@link #allocatorFor}).
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  static Iso<?, ?> liftMapIntoTargetRaw(final Iso<Object, Object> elementIso, final Type srcType, final Type tgtType) {
    final var srcRaw = rawClassOf(srcType);
    final var tgtRaw = rawClassOf(tgtType);
    final var kind = ContainerView.Kind.MAP_VALUES;
    final boolean copies =
      elementIso == Iso.<Object>identity() && copiesBothWays(srcRaw, srcType, tgtRaw, tgtType, kind);
    final var srcCopy = copies ? copyConstructorIfRefused(srcRaw, srcType, kind, tgtRaw) : null;
    final var tgtCopy = copies ? copyConstructorIfRefused(tgtRaw, tgtType, kind, srcRaw) : null;
    final var srcAlloc = srcCopy != null ? null : allocatorFor(srcRaw, srcType, kind);
    final var tgtAlloc = tgtCopy != null ? null : allocatorFor(tgtRaw, tgtType, kind);
    // MethodHandle entry-loop over the value element's raw handle when it is a composed-handle
    // leaf;
    // keys pass through verbatim. Null value Iso => keep the Java loop.
    final var mh = srcCopy == null && tgtCopy == null ? MhIso.liftMap(elementIso, srcAlloc, tgtAlloc) : null;
    // A side that keeps its keys in order has to see each key it inserts, to name one it cannot
    // order, and the fused loop offers nowhere to stand between the two. Only that side gives the
    // fused loop up.
    final boolean loopForward = SortedMap.class.isAssignableFrom(tgtRaw);
    final boolean loopBackward = SortedMap.class.isAssignableFrom(srcRaw);
    if (mh != null && !loopForward && !loopBackward) return mh;
    return Iso.of(
      src ->
        tgtCopy != null
          ? copied(src, tgtCopy)
          : mh != null && !loopForward
            ? mh.to(src)
            : buildMap(src, tgtAlloc, elementIso::to, tgtRaw),
      tgt ->
        srcCopy != null
          ? copied(tgt, srcCopy)
          : mh != null && !loopBackward
            ? mh.from(tgt)
            : buildMap(tgt, srcAlloc, elementIso::from, srcRaw)
    );
  }

  /**
   * Whether a pair whose elements pass through unchanged is built by copy constructor where the
   * shared allocation rules can allocate nothing for a side. They allow it only where each side can
   * be built from the other, and neither is a side its builder makes, which is the condition the
   * generated bridge copies inline under.
   */
  private static boolean copiesBothWays(
    final Class<?> srcRaw,
    final Type srcType,
    final Class<?> tgtRaw,
    final Type tgtType,
    final ContainerView.Kind kind
  ) {
    return (
      ALLOCATION.copiesInPlace(tgtType, kind, srcType, null) &&
      ALLOCATION.copiesInPlace(srcType, kind, tgtType, null) &&
      !builtByBuilder(srcRaw, srcType, kind) &&
      !builtByBuilder(tgtRaw, tgtType, kind)
    );
  }

  /** Whether the shared rules allocate nothing for this side and its builder builds it instead. */
  private static boolean builtByBuilder(final Class<?> raw, final Type declared, final ContainerView.Kind kind) {
    return (
      ALLOCATION.allocate(declared, kind, null) instanceof Allocation.Refuse &&
      Beans.intermediateAllocator(raw).get() != null
    );
  }

  /**
   * The copy constructor a side the shared rules allocate nothing for is built by, handed the other
   * side's container, or null for a side with an allocation to fill. Where several accept that
   * container, the one with the narrowest parameter is the one Java's overload resolution binds, so
   * it is the one the generated {@code new} calls too.
   */
  private static Function<Object, Object> copyConstructorIfRefused(
    final Class<?> raw,
    final Type declared,
    final ContainerView.Kind kind,
    final Class<?> from
  ) {
    if (!(ALLOCATION.allocate(declared, kind, null) instanceof Allocation.Refuse)) return null;
    Class<?> parameter = null;
    for (final var ctor : raw.getConstructors()) {
      if (ctor.getParameterCount() != 1 || !ctor.getParameterTypes()[0].isAssignableFrom(from)) continue;
      final var candidate = ctor.getParameterTypes()[0];
      if (parameter == null || parameter.isAssignableFrom(candidate)) parameter = candidate;
    }
    final var handle = parameter == null ? null : Beans.publicConstructor(raw, parameter);
    if (handle == null) return null;
    return input -> {
      try {
        return handle.invoke(input);
      } catch (final Throwable t) {
        throw new IllegalStateException("Deep map: " + canonical(raw) + " refused its copy constructor", t);
      }
    };
  }

  /** {@code copy} applied to a container, which a null one passes through untouched. */
  private static Object copied(final Object input, final Function<Object, Object> copy) {
    return input == null ? null : copy.apply(input);
  }

  /**
   * The allocator a lift fills for a container declared as {@code declared}, built as {@code kind}.
   *
   * <p>Which class is built, and through which call, is the shared allocation rules' decision, the
   * same one the generated bridge renders as text. What is left here is making the call. A class
   * the shared table names, or a family default standing in for an interface or abstract type, is
   * written out by name: {@code java.base} constructors cannot be bound through {@code
   * LambdaMetafactory}, and a direct {@code new} needs no reachability metadata under native image.
   * A class built as itself is bound through its no-argument constructor, and a source's order is
   * carried into it where it keeps one.
   *
   * <p>Where the rules refuse, a static {@code builder()} is the one route left, as it is on the
   * generated path; a class with none is refused in the rules' words, while the plan is built.
   */
  private static Function<Object, Object> allocatorFor(
    final Class<?> raw,
    final Type declared,
    final ContainerView.Kind kind
  ) {
    final var decision = ALLOCATION.allocate(declared, kind, null);
    if (decision instanceof Allocation.Build build) {
      final var named = rendered(build, kind, build.call() == Allocation.Call.KEY_CLASS ? keyClassOf(declared) : null);
      if (named != null) return named;
      // Probing means calling, so a constructor that throws fails here, while the plan is built,
      // rather than once per conversion afterwards.
      final var ctor = Beans.noArgConstructor(raw);
      if (ctor != null && ctor.get() != null) return orderingAware(raw, declared, kind, ignored -> ctor.get());
    }
    final var builder = Beans.intermediateAllocator(raw);
    if (builder.get() != null) return orderingAware(raw, declared, kind, ignored -> builder.get());
    throw new IllegalStateException(
      decision instanceof Allocation.Refuse refuse
        ? refuse.reason()
        : PairingMessages.noReachableConstructor(canonical(raw))
    );
  }

  /**
   * The allocator an element-wise collection lift fills, which stages a copy-on-write container's
   * elements before {@link #finishCollection} copies them across.
   */
  private static Function<Object, Object> liftAllocatorFor(
    final Class<?> raw,
    final Type declared,
    final ContainerView.Kind kind
  ) {
    final var staged = copyOnWriteAllocator(raw);
    return staged != null ? staged : allocatorFor(raw, declared, kind);
  }

  /**
   * The copy-on-write containers, which no table row names because what sets them apart is how they
   * are filled rather than which class they are: above one element the lift stages the elements in
   * a list and {@link #finishCollection} copies them across in one step, instead of copying the
   * whole array once per element added.
   */
  private static Function<Object, Object> copyOnWriteAllocator(final Class<?> raw) {
    if (raw == CopyOnWriteArrayList.class) return input -> {
      final int size = ((Collection<?>) input).size();
      return size <= 1 ? new CopyOnWriteArrayList<>() : new ArrayList<>(size);
    };
    if (raw == CopyOnWriteArraySet.class) return input -> {
      final int size = ((Collection<?>) input).size();
      return size <= 1 ? new CopyOnWriteArraySet<>() : new ArrayList<>(size);
    };
    return null;
  }

  /** The class of a declared map's keys, which a container built from its key class is handed. */
  private static Class<?> keyClassOf(final Type declared) {
    return rawClassOf(RULES.containerViewOf(declared).keyType());
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
   * <p>The JDK's own sorted classes are allocated by name, with the source's comparator handed to a
   * constructor that takes one. A subtype answers to none of those names, and Java does not inherit
   * constructors, so whether it can receive a comparator is the shared rules' decision. Where it
   * can, that constructor is used. Where it cannot, a source ordered by a comparator has nowhere to
   * put it, and a rebuild would reorder by the elements' own {@code compareTo} while producing a
   * container of the right type and size — so it fails instead of returning something quietly
   * different. A source ordered naturally loses nothing and is allocated as before.
   */
  private static Function<Object, Object> orderingAware(
    final Class<?> raw,
    final Type declared,
    final ContainerView.Kind kind,
    final Function<Object, Object> plain
  ) {
    return switch (RULES.orderingFor(declared, raw, kind, true)) {
      case Ordering.None<Type> none -> plain;
      case Ordering.Refuse<Type> refuse -> input -> {
        refuseCarriedComparator(input, kind, refuse.reason());
        return plain.apply(input);
      };
      case Ordering.Carry<Type> carry -> carrying(raw, kind, plain);
    };
  }

  /**
   * An allocator that hands the source's comparator to the class's comparator constructor, which
   * the shared rules found callable through the lookup that binds its no-argument one.
   */
  private static Function<Object, Object> carrying(
    final Class<?> raw,
    final ContainerView.Kind kind,
    final Function<Object, Object> plain
  ) {
    // The shared rules decide to carry only where this same binding found the constructor.
    final var ctor = Objects.requireNonNull(Beans.publicConstructor(raw, Comparator.class));
    return input -> {
      final var comparator = comparatorOf(kind, input);
      // Natural ordering is what the no-argument constructor already produces, and a constructor
      // taking a comparator is free to reject a null one.
      if (comparator == null) return plain.apply(input);
      try {
        return ctor.invoke(comparator);
      } catch (final Throwable t) {
        throw new IllegalStateException("Deep map: " + canonical(raw) + " refused its Comparator constructor", t);
      }
    };
  }

  /** The class a declared container type erases to. */
  private static Class<?> rawClassOf(final Type type) {
    return (Class<?>) PROPS.rawType(type);
  }

  /**
   * The name a nested class carries in source, which is the spelling the generated path reports.
   */
  private static String canonical(final Class<?> raw) {
    return raw.getCanonicalName() == null ? raw.getName() : raw.getCanonicalName();
  }

  /** The comparator a source container of this family is ordered by, or null where it has none. */
  private static Comparator<Object> comparatorOf(final ContainerView.Kind kind, final Object input) {
    return kind == ContainerView.Kind.MAP_VALUES ? mapComparator(input) : setComparator(input);
  }

  @SuppressWarnings("unchecked")
  private static Comparator<Object> mapComparator(final Object input) {
    return input instanceof SortedMap<?, ?> sorted ? (Comparator<Object>) sorted.comparator() : null;
  }

  @SuppressWarnings("unchecked")
  private static Comparator<Object> setComparator(final Object input) {
    return input instanceof SortedSet<?> sorted ? (Comparator<Object>) sorted.comparator() : null;
  }

  /** The reflection handles the shared rules read through. */
  private static final ReflectionProps PROPS = new ReflectionProps();

  /** The shared rules, over reflection handles. */
  private static final PairingRules<Type> RULES = new PairingRules<>(PROPS);

  /** The shared allocation rules, over reflection handles. */
  private static final ContainerAllocation<Type> ALLOCATION = new ContainerAllocation<>(PROPS);

  /**
   * The allocator the shared table asks for, or null where it names nothing.
   *
   * <p>Only the table is asked, not the rules layered on it, so a class the table does not name
   * answers null rather than being bound. A container built from its key class is handed the class
   * the source map was built from, which is the declared one wherever this is reached: an interface
   * is never built that way, and a pair of one declared type reads an instance of that type.
   */
  private static Function<Object, Object> specAllocatorFor(final Class<?> raw, final ContainerView.Kind kind) {
    final var decision = RULES.allocationFor(raw, kind);
    if (decision == null) return null;
    if (decision instanceof Allocation.Refuse refuse) throw new IllegalStateException(refuse.reason());
    return rendered((Allocation.Build) decision, kind, null);
  }

  /**
   * A decided allocation as a function of the source, or null where {@code build} names a class
   * none of the calls below write out by name, which is a class built as itself.
   *
   * <p>The call is dispatched on before the class is, so a decision that names the wrong
   * constructor reaches a group that does not know the class and yields nothing, rather than
   * quietly building the right class the wrong way.
   *
   * @param keyClass the declared key class a container built from one is handed, or null to read it
   *     off the source, which is then a map of that same class
   */
  private static Function<Object, Object> rendered(
    final Allocation.Build build,
    final ContainerView.Kind kind,
    final Class<?> keyClass
  ) {
    return switch (build.call()) {
      case NO_ARG -> noArg(build.implName());
      case COUNT -> fromCount(build.implName(), kind);
      case TABLE_FACTORY -> fromTableFactory(build.implName(), kind);
      case TABLE_ARITHMETIC -> fromTableArithmetic(build.implName());
      case ORDERING -> fromOrdering(build.implName(), kind);
      case KEY_CLASS -> fromKeyClass(build.implName(), keyClass);
    };
  }

  private static Function<Object, Object> noArg(final String implName) {
    return switch (implName) {
      case "java.util.LinkedList" -> ignored -> new LinkedList<>();
      case "java.util.Stack" -> ignored -> new Stack<>();
      default -> null;
    };
  }

  private static Function<Object, Object> fromCount(final String implName, final ContainerView.Kind kind) {
    return switch (implName) {
      case "java.util.ArrayList" -> input -> new ArrayList<>(count(input, kind));
      case "java.util.ArrayDeque" -> input -> new ArrayDeque<>(count(input, kind));
      case "java.util.Vector" -> input -> new Vector<>(count(input, kind));
      case "java.util.IdentityHashMap" -> input -> new IdentityHashMap<>(count(input, kind));
      case "java.util.concurrent.ConcurrentHashMap" -> input -> new ConcurrentHashMap<>(count(input, kind));
      default -> null;
    };
  }

  private static Function<Object, Object> fromTableFactory(final String implName, final ContainerView.Kind kind) {
    return switch (implName) {
      case "java.util.LinkedHashSet" -> input -> LinkedHashSet.newLinkedHashSet(count(input, kind));
      case "java.util.HashSet" -> input -> HashSet.newHashSet(count(input, kind));
      case "java.util.LinkedHashMap" -> input -> LinkedHashMap.newLinkedHashMap(count(input, kind));
      case "java.util.HashMap" -> input -> HashMap.newHashMap(count(input, kind));
      default -> null;
    };
  }

  private static Function<Object, Object> fromTableArithmetic(final String implName) {
    return switch (implName) {
      // No newWeakHashMap factory exists, so the arithmetic the hash factories do internally is
      // done here instead.
      case "java.util.WeakHashMap" -> input -> new WeakHashMap<>(capacityFor(((Map<?, ?>) input).size()));
      default -> null;
    };
  }

  private static Function<Object, Object> fromOrdering(final String implName, final ContainerView.Kind kind) {
    return switch (implName) {
      case "java.util.TreeSet" -> input -> new TreeSet<>(setComparator(input));
      case "java.util.concurrent.ConcurrentSkipListSet" -> input -> new ConcurrentSkipListSet<>(setComparator(input));
      case "java.util.TreeMap" -> input -> new TreeMap<>(mapComparator(input));
      case "java.util.concurrent.ConcurrentSkipListMap" -> input -> new ConcurrentSkipListMap<>(mapComparator(input));
      default -> null;
    };
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Function<Object, Object> fromKeyClass(final String implName, final Class<?> keyClass) {
    if (!EnumMap.class.getName().equals(implName)) return null;
    if (keyClass != null) return ignored -> new EnumMap(keyClass);
    // An EnumMap's own copy constructor is the one public way to learn its key class from an
    // instance, so the source is copied and emptied.
    return input -> {
      final var fresh = new EnumMap((EnumMap) input);
      fresh.clear();
      return fresh;
    };
  }

  private static int count(final Object input, final ContainerView.Kind kind) {
    return kind == ContainerView.Kind.MAP_VALUES ? ((Map<?, ?>) input).size() : ((Collection<?>) input).size();
  }
}
