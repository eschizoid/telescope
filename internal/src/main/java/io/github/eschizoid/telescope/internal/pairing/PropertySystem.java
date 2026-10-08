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

  /**
   * Who can call a class's no-argument constructor. {@code PACKAGE} covers protected as well as
   * package-private: a rebuild subclasses nothing, so a protected constructor reaches it only from
   * the constructor's own package, exactly as a package-private one does.
   */
  enum Access {
    /** No constructor taking no arguments, or a class that cannot be instantiated at all. */
    NONE,
    PRIVATE,
    PACKAGE,
    PUBLIC,
  }

  /** Whether both sides of a same-kind subtype copy can actually be allocated. */
  enum Allocability {
    /** Both sides allocable — the copy is buildable. */
    ALLOCABLE,
    /** At least one side provably not allocable — the pair falls through to the next branch. */
    NOT_ALLOCABLE,
    /**
     * This world can't tell: the compile-time adapter, for a concrete class whose constructor it
     * does not probe. {@link PairingRules} resolves the uncertainty in the accepting direction, and
     * the generated code's own allocation checks then refuse a class it cannot construct.
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

  /** Whether {@code t} is an interface or an abstract class: a type with no instance of its own. */
  boolean isAbstractType(T t);

  /**
   * Whether an instance of the class named {@code className}, by its binary name, is an instance of
   * {@code t}'s erasure. False when this world cannot find that class.
   */
  boolean isImplementedBy(T t, String className);

  /** Who can call {@code t}'s declared no-argument constructor. */
  Access noArgConstructorAccess(T t);

  /** The qualified name of the package {@code t} is declared in, empty for the unnamed package. */
  String packageName(T t);

  /**
   * Whether {@code t}'s erasure declares a public constructor taking one parameter that a value of
   * {@code argument}'s erasure can be passed to. Compared by erasure, because a parameter written
   * {@code Collection<? extends E>} names a variable nothing here has bound.
   */
  boolean hasPublicConstructorAccepting(T t, T argument);

  /** The class named {@code binaryName}, or null where this world cannot find it. */
  T typeNamed(String binaryName);

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

  /**
   * Whether {@code t} is a type variable or has one anywhere inside it: among its type arguments,
   * at any depth, in a wildcard's bounds, or as an array's component. {@code List<List<?>>} answers
   * false and {@code List<E>} answers true, so a false answer means every part of the type names a
   * type rather than a parameter waiting to be bound.
   */
  boolean mentionsTypeVariable(T t);

  /** Type arguments when {@code t} is parameterized; empty list otherwise. */
  List<T> typeArguments(T t);

  /**
   * Actual arguments of the specified generic supertype, after substituting inherited variables.
   */
  List<T> typeArgumentsAs(T t, WellKnown supertype);

  /** The raw/erased class handle of {@code t} ({@code List<X>} → {@code List}). */
  T rawType(T t);

  /**
   * Whether a same-kind collection/map pair can actually be element-copied. An interface or
   * abstract class is built through the default implementation {@link
   * PairingRules#hasDefaultImplementation} names, in both worlds alike, and is not allocable where
   * it names none. A concrete class is a fact per world: the reflection adapter probes the real
   * intermediate allocator, and the compile-time adapter answers {@link Allocability#UNKNOWN}. The
   * uncertainty POLICY (proceed as copyable) lives in {@link PairingRules}, not here.
   */
  Allocability copyAllocability(T src, T tgt);

  /** Human-readable type name for diagnostics — {@code Type#getTypeName} semantics. */
  String typeName(T t);

  /**
   * The name a class carries in source, a nested class's enclosing classes joined to it by dots,
   * which is how a refusal that names a class spells it in both worlds.
   */
  String sourceName(T t);

  /**
   * The parameter type of {@code impl}'s single-argument constructor taking a {@code Comparator},
   * with {@code impl}'s type parameters replaced, in the order {@code impl} declares them, by
   * {@code arguments}. Empty {@code arguments} replace nothing, which reads the parameter over
   * {@code impl}'s own type variables, the way a class used raw is read. Null where there is no
   * constructor this world can call, and where {@code impl} declares type parameters and a
   * non-empty {@code arguments} does not supply exactly one for each, since pairing them off by
   * position would then substitute the wrong ones.
   *
   * <p>Java forbids two constructors with one erasure, so there is at most one to find. It counts
   * where it is public and the class can be built at all, which is decided before this is asked:
   * the constructor is the alternative to a no-argument one on the same class, so whatever reaches
   * that class reaches this constructor with it. A generated bridge names the class in the same
   * allocation either way, and the reflective path binds both constructors through the same lookup.
   */
  T comparatorParameter(T impl, List<T> arguments);
}
