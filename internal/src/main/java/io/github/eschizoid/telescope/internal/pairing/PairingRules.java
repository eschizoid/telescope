package io.github.eschizoid.telescope.internal.pairing;

import io.github.eschizoid.telescope.internal.pairing.PropertySystem.WellKnown;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    if (srcView != null && tgtView != null && srcView.kind() == tgtView.kind()) {
      // Map<K, X> ↔ Map<K, Y>: keys must match exactly; lifting preserves the source keys.
      if (srcView.kind() == ContainerView.Kind.MAP_VALUES && !props.sameType(srcView.keyType(), tgtView.keyType())) {
        return new PairDecision.Incompatible<>(
          PairingMessages.incompatibleMapKeys(
            componentName,
            props.typeName(srcView.keyType()),
            props.typeName(tgtView.keyType())
          )
        );
      }
      return new PairDecision.LiftContainer<>(srcView, tgtView);
    }

    return new PairDecision.Incompatible<>(
      PairingMessages.incompatibleShapes(componentName, props.typeName(srcType), props.typeName(tgtType))
    );
  }

  /**
   * The container view of {@code t}, or {@code null} when {@code t} is not a parameterized
   * container the auto-lift understands. Selection rules: {@code Optional} (final, exact) →
   * OPTIONAL; any {@code List} / {@code Set} subtype → LIST / SET; any {@code Map} subtype whose
   * key argument is a plain class handle → MAP_VALUES (a non-class key — wildcard, type variable,
   * or parameterized type — defeats the key-equality guarantee, so the type is not treated as a
   * liftable container).
   */
  public ContainerView<T> containerViewOf(final T t) {
    final var args = props.typeArguments(t);
    if (args.isEmpty()) return null;
    final var raw = props.rawType(t);
    if (props.isSubtypeOf(raw, WellKnown.OPTIONAL)) {
      return new ContainerView<>(ContainerView.Kind.OPTIONAL, args.getFirst(), null, raw);
    }
    if (props.isSubtypeOf(raw, WellKnown.LIST)) {
      return new ContainerView<>(ContainerView.Kind.LIST, args.getFirst(), null, raw);
    }
    if (props.isSubtypeOf(raw, WellKnown.SET)) {
      return new ContainerView<>(ContainerView.Kind.SET, args.getFirst(), null, raw);
    }
    if (props.isSubtypeOf(raw, WellKnown.MAP)) {
      final var key = args.get(0);
      if (!props.isClassType(key)) return null;
      return new ContainerView<>(ContainerView.Kind.MAP_VALUES, args.get(1), key, raw);
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
}
