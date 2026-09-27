package io.github.eschizoid.telescope.internal.pairing;

import io.github.eschizoid.telescope.internal.pairing.PropertySystem.WellKnown;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared pairing decision rules — one implementation, two consumers. The runtime mapper
 * construction and the compile-time verifier both delegate every pairing decision here: which
 * conversion a (source, target) field-type pair takes ({@link #decidePair}), which fields of a type
 * pair match by name ({@link #matchFields}), and which container view a parameterized type presents
 * ({@link #containerViewOf}). Only the type-system <em>primitives</em> differ per world — supplied
 * through {@link PropertySystem} — so the rules cannot drift between compile time and construction
 * time.
 *
 * <p>Decision order in {@link #decidePair} is load-bearing — it IS the runtime lattice: identity →
 * primitive/wrapper → same-kind subtype copy → reflectable recursion → cross-{@code Optional}
 * bridge → same-kind container lift → incompatible. Subtype copy must precede reflectable
 * recursion: a raw container subclass ({@code class ImageUrls extends ArrayList<ImageUrl>}) counts
 * as reflectable, and bean-decomposing it would fail at the JDK boundary (private lookup into
 * {@code java.base} is rejected) — the copy branch intercepts those pairs first.
 *
 * @param <T> the world's type handle
 */
public final class PairingRules<T> {

  private final PropertySystem<T> props;

  public PairingRules(final PropertySystem<T> props) {
    this.props = props;
  }

  /** Decide the conversion for one (source type, target type) field pair. Never returns null. */
  public PairDecision<T> decidePair(final T srcType, final T tgtType, final String componentName) {
    // (a) Same type → identity.
    if (props.sameType(srcType, tgtType)) return new PairDecision.Identity<>();

    if (props.isClassType(srcType) && props.isClassType(tgtType)) {
      // (a.1) Primitive ↔ wrapper over the same scalar — null-safe box/unbox.
      if (primitiveWrapperPair(srcType, tgtType)) return new PairDecision.PrimitiveWrapper<>();

      // (a.2) Same-kind Collection / Map subtype pair (raw container subclasses on both sides) —
      // element copy, gated on kind-discriminator agreement AND allocability so a provably
      // infeasible copy falls through to the remaining branches exactly like the runtime. UNKNOWN
      // allocability (the compile-time world can't probe allocators) resolves in the ACCEPTING
      // direction here: CollectionCopy/MapCopy are terminal accepts, so optimism can only defer an
      // error to the construction backstop, never invent one.
      if (
        sameKindCollection(srcType, tgtType) &&
        props.copyAllocability(srcType, tgtType) != PropertySystem.Allocability.NOT_ALLOCABLE
      ) {
        return new PairDecision.CollectionCopy<>();
      }
      if (
        sameKindMap(srcType, tgtType) &&
        props.copyAllocability(srcType, tgtType) != PropertySystem.Allocability.NOT_ALLOCABLE
      ) {
        return new PairDecision.MapCopy<>();
      }

      // (b) Both reflectable (record or bean) → recurse into the nested pair.
      if (reflectable(srcType) && reflectable(tgtType)) return new PairDecision.RecursePair<>();
    }

    // (c) Container views. Cross-Optional bridge first, then same-kind lift.
    final var srcView = containerViewOf(srcType);
    final var tgtView = containerViewOf(tgtType);

    // (c.1) Optional<X> ↔ nullable scalar/record/bean, either direction.
    if (srcView != null && srcView.kind() == ContainerView.Kind.OPTIONAL && tgtView == null) {
      return new PairDecision.OptionalToNullable<>(srcView.elementType(), tgtType);
    }
    if (tgtView != null && tgtView.kind() == ContainerView.Kind.OPTIONAL && srcView == null) {
      return new PairDecision.NullableToOptional<>(srcType, tgtView.elementType());
    }

    // A Collection-declared side takes the other's shape before anything else looks at the pair,
    // so the lift and the allocator are chosen by what is actually being built. Two of them settle
    // on a list, which is what a Collection guarantees on its own: iteration, and nothing about
    // duplicates. List against Set stays a mismatch -- those are different shapes, not one shape
    // named loosely.
    final var src = settledAgainst(srcView, tgtView);
    final var tgt = settledAgainst(tgtView, srcView);

    if (src != null && tgt != null && src.kind() == tgt.kind()) {
      // Map<K, X> ↔ Map<K, Y>: keys must match exactly; lifting preserves the source keys.
      if (src.kind() == ContainerView.Kind.MAP_VALUES && !props.sameType(src.keyType(), tgt.keyType())) {
        return new PairDecision.Incompatible<>(
          PairingMessages.incompatibleMapKeys(
            componentName,
            props.typeName(src.keyType()),
            props.typeName(tgt.keyType())
          )
        );
      }
      return new PairDecision.LiftContainer<>(src, tgt);
    }

    return new PairDecision.Incompatible<>(
      PairingMessages.incompatibleShapes(componentName, props.typeName(srcType), props.typeName(tgtType))
    );
  }

  private static final Set<WellKnown> GENERAL = Set.of(WellKnown.DEQUE, WellKnown.QUEUE, WellKnown.COLLECTION);

  private static final Map<WellKnown, String> GENERAL_NAMES = Map.of(
    WellKnown.DEQUE,
    "java.util.Deque",
    WellKnown.QUEUE,
    "java.util.Queue",
    WellKnown.COLLECTION,
    "java.util.Collection"
  );

  /**
   * A view seen as the other side's kind when it has none of its own. A {@code COLLECTION} against
   * a list or a set becomes that; against another {@code COLLECTION}, or against nothing, it
   * becomes a list.
   *
   * <p>Part of the classification rather than of one consumer's plumbing: a {@code COLLECTION} view
   * names no shape, so anything that pairs two views owes this step before it can ask whether the
   * two kinds are the same. Both the runtime lift and the generated one consume it, and a consumer
   * that skips it sees a kind no lift can build.
   */
  public ContainerView<T> settledAgainst(final ContainerView<T> view, final ContainerView<T> other) {
    if (view == null || view.kind() != ContainerView.Kind.COLLECTION) return view;
    final var against = other == null ? null : other.kind();
    return view.as(
      against == ContainerView.Kind.LIST || against == ContainerView.Kind.SET ? against : ContainerView.Kind.LIST
    );
  }

  /**
   * The container view of {@code t}, or {@code null} when {@code t} is not a parameterized
   * container the auto-lift understands. Selection rules: {@code Optional} (final, exact) →
   * OPTIONAL; any {@code List} subtype, and the {@code Deque} and {@code Queue} interfaces by name
   * → LIST; any {@code Set} subtype → SET; any {@code Map} subtype whose key argument is a plain
   * class handle → MAP_VALUES (a non-class key — wildcard, type variable, or parameterized type —
   * defeats the key-equality guarantee, so the type is not treated as a liftable container); the
   * {@code Collection} interface by name → COLLECTION, which names no shape and is settled against
   * the other side of the pair by {@link #settledAgainst}.
   */
  public ContainerView<T> containerViewOf(final T t) {
    // Raw subclasses retain the explicit shallow-copy policy above. Parameterized subclasses
    // must be viewed through the container supertype: their own parameters can be reordered,
    // fixed, or unrelated to the element/key types.
    if (props.typeArguments(t).isEmpty()) return null;
    final var raw = props.rawType(t);
    // Order decides the answer where a type satisfies more than one: a List is asked as a List
    // before it is asked as a Collection. The last three are what a type reaches only by being
    // none of the others -- a Deque, a Queue, or a field declared as the general Collection -- and
    // they are viewed as lists because that is what they keep: an order, and duplicates.
    for (final var kind : List.of(
      WellKnown.OPTIONAL,
      WellKnown.LIST,
      WellKnown.SET,
      WellKnown.MAP,
      WellKnown.DEQUE,
      WellKnown.QUEUE,
      WellKnown.COLLECTION
    )) {
      if (!props.isSubtypeOf(raw, kind)) continue;
      // The three general kinds are matched by name rather than by subtype. A concrete queue may
      // be capacity-bounded -- a SynchronousQueue holds nothing at all -- and accepting one turns
      // a refusal while the plan is built into a failure on every conversion, which is the worse
      // of the two. A field declared as one of the interfaces asks only for something that keeps
      // an order, and that is answerable.
      if (GENERAL.contains(kind) && !props.typeName(raw).equals(GENERAL_NAMES.get(kind))) continue;
      final var args = props.typeArgumentsAs(t, kind);
      if (kind == WellKnown.MAP) {
        if (args.size() != 2 || !props.isClassType(args.getFirst())) return null;
        return new ContainerView<>(ContainerView.Kind.MAP_VALUES, args.get(1), args.getFirst(), raw);
      }
      if (args.size() != 1) return null;
      return new ContainerView<>(
        switch (kind) {
          case OPTIONAL -> ContainerView.Kind.OPTIONAL;
          // A Deque and a Queue keep an order and admit duplicates, which is what a list is. A
          // field declared as the general Collection has said neither, so it is settled against
          // whatever the other side of the pair turns out to be.
          case LIST, DEQUE, QUEUE -> ContainerView.Kind.LIST;
          case COLLECTION -> ContainerView.Kind.COLLECTION;
          case SET -> ContainerView.Kind.SET;
          default -> throw new AssertionError(kind);
        },
        args.getFirst(),
        null,
        raw
      );
    }
    return null;
  }

  /**
   * A record, or any class the reflective bean machinery can decompose — everything except
   * primitives, arrays, enums, interfaces, and the common scalar families ({@code CharSequence},
   * {@code Number}, {@code Boolean}/{@code Character}, {@code Temporal}, {@code UUID}).
   */
  public boolean reflectable(final T t) {
    if (props.isRecordType(t)) return true;
    if (props.isPrimitive(t)) return false;
    if (props.isArrayType(t)) return false;
    if (props.isEnumType(t)) return false;
    if (props.isInterfaceType(t)) return false;
    if (props.isSubtypeOf(t, WellKnown.CHAR_SEQUENCE)) return false;
    if (props.isSubtypeOf(t, WellKnown.NUMBER)) return false;
    if (props.isSubtypeOf(t, WellKnown.BOOLEAN_WRAPPER) || props.isSubtypeOf(t, WellKnown.CHARACTER_WRAPPER)) {
      return false;
    }
    if (props.isSubtypeOf(t, WellKnown.TEMPORAL)) return false;
    return !props.isSubtypeOf(t, WellKnown.UUID);
  }

  /** Primitive ↔ wrapper pair over the same scalar, in either direction. */
  public boolean primitiveWrapperPair(final T src, final T tgt) {
    return (
      (props.isPrimitive(src) && props.sameType(props.boxed(src), tgt)) ||
      (props.isPrimitive(tgt) && props.sameType(props.boxed(tgt), src))
    );
  }

  /**
   * Both sides are {@code Collection} subtypes that agree on every kind discriminator: the List
   * axis, the Set / SortedSet axis, and (within the Queue residual) the Deque axis. Disagreement on
   * any axis would silently re-interpret container semantics — or throw at copy time — so the pair
   * is rejected here and the user declares an explicit conversion row instead.
   */
  public boolean sameKindCollection(final T a, final T b) {
    if (!props.isSubtypeOf(a, WellKnown.COLLECTION) || !props.isSubtypeOf(b, WellKnown.COLLECTION)) return false;
    final var aList = props.isSubtypeOf(a, WellKnown.LIST);
    final var bList = props.isSubtypeOf(b, WellKnown.LIST);
    if (aList != bList) return false;
    if (aList) return true;
    final var aSet = props.isSubtypeOf(a, WellKnown.SET);
    final var bSet = props.isSubtypeOf(b, WellKnown.SET);
    if (aSet != bSet) return false;
    if (aSet) return props.isSubtypeOf(a, WellKnown.SORTED_SET) == props.isSubtypeOf(b, WellKnown.SORTED_SET);
    if (!props.isSubtypeOf(a, WellKnown.QUEUE) || !props.isSubtypeOf(b, WellKnown.QUEUE)) return false;
    return props.isSubtypeOf(a, WellKnown.DEQUE) == props.isSubtypeOf(b, WellKnown.DEQUE);
  }

  /**
   * Both sides are {@code Map} subtypes agreeing on the SortedMap axis — a {@code HashMap ↔
   * TreeMap} pair over non-Comparable keys would throw at copy time when the fresh sorted map calls
   * {@code compareTo} on the first inserted key, so the crossing is rejected before any conversion
   * runs.
   */
  public boolean sameKindMap(final T a, final T b) {
    if (!props.isSubtypeOf(a, WellKnown.MAP) || !props.isSubtypeOf(b, WellKnown.MAP)) return false;
    return props.isSubtypeOf(a, WellKnown.SORTED_MAP) == props.isSubtypeOf(b, WellKnown.SORTED_MAP);
  }

  /**
   * Same-name field matching over the two sides' property names, honoring claims already made by
   * explicit rows. Pure set logic — the strictness gates (what happens to the unmatched leftovers)
   * are the caller's policy; the matches and leftovers themselves are decided here, identically in
   * both worlds. Iteration order follows the target side, matching construction order.
   */
  public static MatchResult matchFields(
    final List<String> sourceNames,
    final List<String> targetNames,
    final Set<String> claimedSource,
    final Set<String> claimedTarget
  ) {
    final var srcNameSet = new LinkedHashSet<>(sourceNames);
    final var matched = new ArrayList<String>();
    final var unmatchedTargets = new ArrayList<String>();
    final var claimedSrcNow = new LinkedHashSet<>(claimedSource);
    for (final var name : targetNames) {
      if (claimedTarget.contains(name)) continue;
      if (srcNameSet.contains(name)) {
        matched.add(name);
        claimedSrcNow.add(name);
      } else {
        unmatchedTargets.add(name);
      }
    }
    final var unmatchedSources = new ArrayList<String>();
    for (final var name : sourceNames) {
      if (!claimedSrcNow.contains(name)) unmatchedSources.add(name);
    }
    return new MatchResult(List.copyOf(matched), List.copyOf(unmatchedTargets), List.copyOf(unmatchedSources));
  }

  /**
   * Outcome of {@link #matchFields}: field names paired by same-name auto-match (in target order),
   * target names with no source counterpart, and source names with no consumer.
   */
  public record MatchResult(List<String> matched, List<String> unmatchedTargets, List<String> unmatchedSources) {}

  /**
   * The allocation table, keyed on the declared type's own name. A name that settles by kind rather
   * than by itself is answered in {@link #allocationFor} instead of appearing here.
   *
   * <p>Exact names rather than subtype tests, because the question is what a field declared as this
   * type is rebuilt as, and a subtype answers for itself. {@code ArrayList} and {@code ArrayDeque}
   * take an element count; the hash families take a table capacity, which for the same number of
   * elements is a different number; the sorted families take a comparator instead. A type whose
   * {@code int} means something else entirely -- a hard bound on {@code LinkedBlockingQueue}, a
   * capacity {@code PriorityQueue} refuses to see as zero -- is built with no argument at all.
   */
  private static final Map<String, Allocation> BY_DECLARED_NAME = Map.ofEntries(
    Map.entry("java.util.List", new Allocation.Build("java.util.ArrayList", Allocation.Sizing.ELEMENT_COUNT)),
    Map.entry("java.util.ArrayList", new Allocation.Build("java.util.ArrayList", Allocation.Sizing.ELEMENT_COUNT)),
    Map.entry("java.util.LinkedList", new Allocation.Build("java.util.LinkedList", Allocation.Sizing.NONE)),
    Map.entry("java.util.Deque", new Allocation.Build("java.util.ArrayDeque", Allocation.Sizing.ELEMENT_COUNT)),
    Map.entry("java.util.Queue", new Allocation.Build("java.util.ArrayDeque", Allocation.Sizing.ELEMENT_COUNT)),
    Map.entry("java.util.Vector", new Allocation.Build("java.util.Vector", Allocation.Sizing.ELEMENT_COUNT)),
    Map.entry("java.util.Stack", new Allocation.Build("java.util.Stack", Allocation.Sizing.NONE)),
    Map.entry("java.util.PriorityQueue", new Allocation.Build("java.util.PriorityQueue", Allocation.Sizing.NONE)),
    Map.entry(
      "java.util.concurrent.LinkedBlockingQueue",
      new Allocation.Build("java.util.concurrent.LinkedBlockingQueue", Allocation.Sizing.NONE)
    ),
    Map.entry("java.util.Set", new Allocation.Build("java.util.LinkedHashSet", Allocation.Sizing.TABLE_CAPACITY)),
    Map.entry(
      "java.util.LinkedHashSet",
      new Allocation.Build("java.util.LinkedHashSet", Allocation.Sizing.TABLE_CAPACITY)
    ),
    Map.entry("java.util.HashSet", new Allocation.Build("java.util.HashSet", Allocation.Sizing.TABLE_CAPACITY)),
    Map.entry("java.util.TreeSet", new Allocation.Build("java.util.TreeSet", Allocation.Sizing.SOURCE_ORDERING)),
    Map.entry("java.util.SortedSet", new Allocation.Build("java.util.TreeSet", Allocation.Sizing.SOURCE_ORDERING)),
    Map.entry("java.util.NavigableSet", new Allocation.Build("java.util.TreeSet", Allocation.Sizing.SOURCE_ORDERING)),
    Map.entry(
      "java.util.concurrent.ConcurrentSkipListSet",
      new Allocation.Build("java.util.concurrent.ConcurrentSkipListSet", Allocation.Sizing.SOURCE_ORDERING)
    ),
    Map.entry("java.util.Map", new Allocation.Build("java.util.LinkedHashMap", Allocation.Sizing.TABLE_CAPACITY)),
    Map.entry(
      "java.util.LinkedHashMap",
      new Allocation.Build("java.util.LinkedHashMap", Allocation.Sizing.TABLE_CAPACITY)
    ),
    Map.entry("java.util.HashMap", new Allocation.Build("java.util.HashMap", Allocation.Sizing.TABLE_CAPACITY)),
    Map.entry("java.util.TreeMap", new Allocation.Build("java.util.TreeMap", Allocation.Sizing.SOURCE_ORDERING)),
    Map.entry("java.util.SortedMap", new Allocation.Build("java.util.TreeMap", Allocation.Sizing.SOURCE_ORDERING)),
    Map.entry("java.util.NavigableMap", new Allocation.Build("java.util.TreeMap", Allocation.Sizing.SOURCE_ORDERING)),
    Map.entry(
      "java.util.concurrent.ConcurrentHashMap",
      new Allocation.Build("java.util.concurrent.ConcurrentHashMap", Allocation.Sizing.ELEMENT_COUNT)
    ),
    Map.entry(
      "java.util.concurrent.ConcurrentMap",
      new Allocation.Build("java.util.concurrent.ConcurrentHashMap", Allocation.Sizing.ELEMENT_COUNT)
    ),
    Map.entry(
      "java.util.concurrent.ConcurrentSkipListMap",
      new Allocation.Build("java.util.concurrent.ConcurrentSkipListMap", Allocation.Sizing.SOURCE_ORDERING)
    ),
    Map.entry(
      "java.util.IdentityHashMap",
      new Allocation.Build("java.util.IdentityHashMap", Allocation.Sizing.ELEMENT_COUNT)
    ),
    Map.entry("java.util.WeakHashMap", new Allocation.Build("java.util.WeakHashMap", Allocation.Sizing.TABLE_CAPACITY)),
    Map.entry(
      "java.util.EnumMap",
      new Allocation.Refuse(
        "EnumMap targets are not supported via auto-Iso lift — EnumMap has no no-arg" +
          " constructor (it needs the Class<K> key class). Use an explicit" +
          " `Mapping.via(...)` row that constructs the EnumMap with its key" +
          " class."
      )
    )
  );

  /**
   * What this declared container is rebuilt as, or null when the table does not name it and the
   * caller's own fallbacks decide.
   *
   * <p>A type the table does not name is not refused here. Each side can still reach one its own
   * way — a public constructor bound at run time, a family default written into source — and
   * answering for those is the caller's job, not this table's.
   */
  public Allocation allocationFor(final T declared, final ContainerView.Kind kind) {
    final var name = props.typeName(props.rawType(declared));
    // A declaration that names no shape is rebuilt as whatever it was paired against. Collection is
    // the union of the two, so the kind the pair settled on is the only thing that says which.
    if ("java.util.Collection".equals(name)) {
      return kind == ContainerView.Kind.SET
        ? new Allocation.Build("java.util.LinkedHashSet", Allocation.Sizing.TABLE_CAPACITY)
        : new Allocation.Build("java.util.ArrayList", Allocation.Sizing.ELEMENT_COUNT);
    }
    return BY_DECLARED_NAME.get(name);
  }
}
