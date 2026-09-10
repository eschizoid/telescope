package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Placeholder / default-value machinery for {@link DeepMap}'s permissive modes. When a target field
 * has no same-name source counterpart (telescope-row permissive mode, nested auto-recursed pairs,
 * lenient forward-only resolution) or a source field is dropped, the field's slot still needs an
 * {@link Iso} — this class supplies it, type-driven:
 *
 * <ul>
 *   <li>{@link #NULLING_ISO} — null in both directions, the reference-typed default.
 *   <li>primitive targets — the JLS default (0 / false / etc.) so canonical-ctor reflection doesn't
 *       NPE unboxing a null Object.
 *   <li>record / allocatable-bean targets claimed by a telescope-row write — a recursive
 *       default-tree instance, so the post-fixup overlay can descend into a non-null intermediate.
 * </ul>
 *
 * <p>{@link #placeholderIsoFor} is the single entry point for the permissive-mode selection; {@link
 * #primitiveDefault} is also consumed directly by {@code DeepMap}'s primitive ↔ wrapper leaf Iso.
 */
final class Placeholders {

  private Placeholders() {}

  /**
   * Placeholder Iso used by {@code Mapping.drop(srcAccessor)}'s backward pass — both directions
   * return {@code null}. Only ever invoked on the backward pass for source-only fields that have no
   * target counterpart; the forward direction skips the field entirely.
   */
  static final Iso<Object, Object> NULLING_ISO = Iso.of(__ -> null, __ -> null);

  /**
   * Type-aware placeholder Iso for the permissive-mode block in {@code DeepMap}'s recursive
   * resolver. Picks the right "missing source field" filler based on the target field's type and
   * whether a telescope row claims the field as its first hop.
   */
  static Iso<Object, Object> placeholderIsoFor(final Class<?> fieldType, final boolean claimedByTelescopeWrite) {
    if (fieldType == null) return NULLING_ISO;
    if (claimedByTelescopeWrite && (fieldType.isRecord() || BEAN_ALLOCATABLE.get(fieldType))) {
      return defaultAllocatorIso(fieldType);
    }
    if (fieldType.isPrimitive()) {
      final var value = primitiveDefault(fieldType);
      return Iso.of(__ -> value, __ -> value);
    }
    return NULLING_ISO;
  }

  /**
   * Forward-only iso that materialises a fresh default-tree instance of {@code type} on every
   * forward call. Used as the placeholder for telescope-row-claimed target fields that have no
   * same-name source counterpart — the post-fixup overlay descends into the allocated instance and
   * writes the leaf, so a fully-flat source can be lifted into a deeply-nested target without
   * per-hop allocation glue.
   *
   * <p>Records recurse via their canonical constructor with default component values. Beans
   * (JavaBean shape) get a fresh instance from their public no-arg constructor. Anything without a
   * usable construction strategy falls back to {@code null} — the user will see the same downstream
   * null the unannotated path produces today, no worse.
   */
  private static Iso<Object, Object> defaultAllocatorIso(final Class<?> type) {
    return Iso.of(__ -> recursiveDefault(type), __ -> null);
  }

  /** A default-tree instance of {@code type}, built from its cached plan. */
  private static Object recursiveDefault(final Class<?> type) {
    return PLANS.get(type).get();
  }

  /**
   * Whether a type has a usable intermediate-construction strategy. Cached because the probe costs
   * two thrown exceptions for a type with neither a no-arg constructor nor a {@code builder()} —
   * {@code LocalDate}, {@code UUID}, most JDK value types — and both the plan and the placeholder
   * factory need the answer.
   */
  private static final ClassValue<Boolean> BEAN_ALLOCATABLE = new ClassValue<>() {
    @Override
    protected Boolean computeValue(final Class<?> type) {
      return beanIntermediateAllocatable(type);
    }
  };

  /** Types whose plan is mid-resolution on this thread, so a self-reference can be recognised. */
  private static final ThreadLocal<Set<Class<?>>> IN_PROGRESS = ThreadLocal.withInitial(HashSet::new);

  /**
   * The default-tree plan for a type, resolved once per class: primitives yield their JLS default,
   * records their canonical constructor filled with the same scheme, beans a fresh instance from a
   * public no-arg constructor or a static {@code builder()}, and anything else {@code null}.
   * Everything reflective lives here — the component list, the primitive lookup, the constructor
   * probe — so a conversion pays a supplier call per component rather than re-deriving the shape.
   *
   * <p>The plan is cached, not the value. Today's write paths rebuild rather than mutate, so a
   * shared instance would not be observable through them — but a default is a mutable object handed
   * to arbitrary downstream writes on any thread, and one shared across every conversion of a type
   * is a hazard waiting for the first write path that does mutate in place. Building per call costs
   * one allocation and removes the question.
   */
  private static final ClassValue<Supplier<Object>> PLANS = new ClassValue<>() {
    @Override
    protected Supplier<Object> computeValue(final Class<?> type) {
      return planFor(type);
    }
  };

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static Supplier<Object> planFor(final Class<?> type) {
    if (type.isPrimitive()) {
      final var value = primitiveDefault(type);
      return () -> value;
    }
    if (type.isRecord()) {
      // A record CAN reference itself, directly or mutually — `record Node(Node next, String v)`
      // compiles — and no instance of one can be constructed without an instance of it, so it has
      // no default. Resolving component plans would otherwise re-enter this type forever, so a
      // type already being resolved on this thread yields the same null a type with no usable
      // construction strategy yields.
      if (!IN_PROGRESS.get().add(type)) return () -> null;
      try {
        return recordPlan(type);
      } finally {
        IN_PROGRESS.get().remove(type);
      }
    }
    // Bean intermediate: a public no-arg constructor, or a static builder() (Lombok @Builder,
    // Immutables). JDK scalars and containers are excluded so the records path is unchanged. A
    // telescope-row write goes through the bean's setters at each hop, so an intermediate only has
    // to be non-null and fresh — the allocator is cached per class and hands back a new instance
    // per call, which two conversions writing through the same type both need. A type with neither
    // strategy yields null, the same filler the unannotated path produces.
    if (BEAN_ALLOCATABLE.get(type)) return Beans.intermediateAllocator(type)::get;
    return () -> null;
  }

  /** Component plans in canonical order, filling a positional argument array per call. */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static Supplier<Object> recordPlan(final Class<?> type) {
    final var comps = type.getRecordComponents();
    final var parts = (Supplier<Object>[]) new Supplier<?>[comps.length];
    for (var i = 0; i < comps.length; i++) parts[i] = PLANS.get(comps[i].getType());
    return () -> {
      final var args = new Object[parts.length];
      for (var i = 0; i < parts.length; i++) args[i] = parts[i].get();
      return Records.construct((Class) type, args);
    };
  }

  static Object primitiveDefault(final Class<?> p) {
    if (p == int.class) return 0;
    if (p == long.class) return 0L;
    if (p == boolean.class) return false;
    if (p == double.class) return 0.0;
    if (p == float.class) return 0.0f;
    if (p == byte.class) return (byte) 0;
    if (p == short.class) return (short) 0;
    if (p == char.class) return (char) 0;
    return null;
  }

  // True when the bean is plausibly an intermediate-allocatable user-domain type — has either a
  // public no-arg constructor or a static no-arg builder() method (Lombok @Builder / Immutables).
  // Excludes JDK scalars / containers that happen to have public no-arg ctors we don't want to
  // materialise as defaults.
  private static boolean beanIntermediateAllocatable(final Class<?> type) {
    if (type.isPrimitive() || type.isInterface() || type.isArray()) return false;
    if (type == String.class || Number.class.isAssignableFrom(type) || type == Boolean.class) return false;
    try {
      final var ctor = type.getDeclaredConstructor();
      if (Modifier.isPublic(ctor.getModifiers())) return true;
    } catch (final NoSuchMethodException ignored) {
      // try the builder path next
    }
    try {
      final var builderMethod = type.getMethod("builder");
      return Modifier.isStatic(builderMethod.getModifiers()) && Modifier.isPublic(builderMethod.getModifiers());
    } catch (final NoSuchMethodException ignored) {
      return false;
    }
  }
}
