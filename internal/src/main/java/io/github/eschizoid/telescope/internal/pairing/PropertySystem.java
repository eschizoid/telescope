package io.github.eschizoid.telescope.internal.pairing;

import java.util.List;

/**
 * The type-system primitives {@link PairingRules} is parameterized over. Two worlds implement it: a
 * reflection-backed adapter over {@code java.lang.reflect.Type} (runtime mapper construction) and a
 * {@code javax.lang.model}-backed adapter over {@code TypeMirror} (compile-time verification).
 * Every method is a <em>primitive</em> — a single type-system fact with no policy — so all pairing
 * policy (branch ordering, kind discriminators, scalar exclusions, matching) lives once, in {@link
 * PairingRules}, and cannot drift between the two worlds. Keep every method abstract: the world
 * adapters rely on compile-time breakage when this interface grows, and a {@code default} method
 * here would be a policy leak by construction.
 *
 * @param <T> the world's type handle ({@code Type} or {@code TypeMirror})
 */
public interface PropertySystem<T> {
  /** Well-known JDK types the pairing rules discriminate on. */
  enum WellKnown {
    COLLECTION,
    LIST,
    SET,
    SORTED_SET,
    QUEUE,
    DEQUE,
    MAP,
    SORTED_MAP,
    OPTIONAL,
    CHAR_SEQUENCE,
    NUMBER,
    TEMPORAL,
    UUID,
    BOOLEAN_WRAPPER,
    CHARACTER_WRAPPER,
    COMPARABLE,
  }

  /** Whether both sides of a same-kind subtype copy can actually be allocated. */
  enum Allocability {
    /** Both sides allocable — the copy is buildable. */
    ALLOCABLE,
    /** At least one side provably not allocable — the pair falls through to the next branch. */
    NOT_ALLOCABLE,
    /**
     * This world can't tell (the compile-time adapter). {@link PairingRules} resolves the
     * uncertainty in the accepting direction — an infeasible copy then surfaces at the
     * construction-time backstop rather than as a speculative compile error.
     */
    UNKNOWN,
  }

  /**
   * Structural type equality.
   *
   * <p>Two wildcards are the same type when they have the same upper and lower bounds, with an
   * unwritten upper bound read as {@code Object}, so {@code ?} and {@code ? extends Object} are the
   * same. That is what {@code WildcardType#equals} answers in the reflection world. {@code
   * Types#isSameType} answers false for any wildcard, itself included, so an adapter over it has to
   * compare wildcards itself.
   */
  boolean sameType(T a, T b);

  /**
   * True when {@code t} is a raw class handle — a primitive, an array, or a non-parameterized,
   * non-wildcard reference the rules may probe for record-ness, bean-ness, or subtype-copy pairing.
   * (In the reflection world all three are {@code Class} instances; the mirror world must agree.)
   * Parameterized container types answer {@code false} and flow to {@link
   * PairingRules#containerViewOf} instead.
   */
  boolean isClassType(T t);

  boolean isPrimitive(T t);

  /** The boxed counterpart of a primitive handle; non-primitives are returned unchanged. */
  T boxed(T t);

  boolean isRecordType(T t);

  boolean isArrayType(T t);

  boolean isEnumType(T t);

  boolean isInterfaceType(T t);

  /** Subtype test against a well-known JDK type. Final well-knowns make this an exact match. */
  boolean isSubtypeOf(T t, WellKnown wellKnown);

  /**
   * Whether a value of type {@code from} can be assigned to a variable of type {@code to}:
   * subtyping through the generic supertypes, with a wildcard argument of {@code to} containing
   * whatever its bounds admit; a type variable through any of its bounds; arrays by their
   * components; boxing and unboxing. A generic class used raw is assignable to any parameterization
   * of a supertype, which is the unchecked conversion. Widening between primitive types is not
   * covered, so an adapter may answer either way for it.
   */
  boolean isAssignable(T from, T to);

  /** Whether {@code t} is a wildcard type argument. */
  boolean isWildcard(T t);

  /** A wildcard's lower bound, or null when {@code t} has none or is not a wildcard. */
  T lowerBound(T t);

  /** Type arguments when {@code t} is parameterized; empty list otherwise. */
  List<T> typeArguments(T t);

  /**
   * Actual arguments of the specified generic supertype, after substituting inherited variables.
   */
  List<T> typeArgumentsAs(T t, WellKnown supertype);

  /** The raw/erased class handle of {@code t} ({@code List<X>} → {@code List}). */
  T rawType(T t);

  /**
   * Whether a same-kind collection/map subtype pair can actually be element-copied. A pure fact per
   * world: the reflection adapter probes the real intermediate allocator; the compile-time adapter
   * answers {@link Allocability#UNKNOWN}. The uncertainty POLICY (proceed as copyable) lives in
   * {@link PairingRules}, not here.
   */
  Allocability copyAllocability(T src, T tgt);

  /** Human-readable type name for diagnostics — {@code Type#getTypeName} semantics. */
  String typeName(T t);
}
