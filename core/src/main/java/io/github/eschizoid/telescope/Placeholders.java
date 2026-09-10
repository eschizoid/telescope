package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.lang.reflect.Modifier;
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
    if (claimedByTelescopeWrite && (fieldType.isRecord() || beanIntermediateAllocatable(fieldType))) {
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

  /**
   * Construct a default-tree instance of {@code type} — primitives get their JLS default (0, false,
   * etc.), records recurse via their canonical constructor with the same scheme, beans get a fresh
   * instance from their public no-arg constructor (uninitialised fields default to null/zero, which
   * the telescope-row write then overwrites). Anything else returns {@code null}.
   *
   * <p>Cycles between record types can't arise in practice: each canonical ctor needs every other
   * type already constructible, so a record cycle would fail at compile time. Bean cycles are
   * possible in principle but the no-arg ctor doesn't recurse into fields, so a self-referencing
   * bean is handled with a single allocation regardless of its field shape.
   */
  private static Object recursiveDefault(final Class<?> type) {
    return PLANS.get(type).get();
  }

  /**
   * The default-tree plan for a type, resolved once per class. Everything reflective about building
   * a default lives here — the component list, the primitive lookup, and the constructor probe — so
   * a conversion pays a supplier call per component rather than re-deriving the shape.
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
      // Canonical order, so the component plans fill a positional array directly. Records cannot
      // form a construction cycle — every canonical constructor needs its component types already
      // constructible — so resolving a component's plan here cannot re-enter this type.
      final var comps = type.getRecordComponents();
      final var parts = (Supplier<Object>[]) new Supplier<?>[comps.length];
      for (var i = 0; i < comps.length; i++) parts[i] = PLANS.get(comps[i].getType());
      return () -> {
        final var args = new Object[parts.length];
        for (var i = 0; i < parts.length; i++) args[i] = parts[i].get();
        return Records.construct((Class) type, args);
      };
    }
    // Bean intermediate: try the public no-arg ctor first, falling back to the static builder()
    // pattern (Lombok @Builder, Immutables-style). Skip JDK scalars / containers entirely so the
    // records path stays unchanged. Telescope-row writes go through the bean's setters at each
    // hop, so each intermediate just needs to be non-null; the setters overwrite the
    // default-initialised fields. If neither strategy works, the cached supplier yields null —
    // same behaviour as before bean-intermediate support, but no per-call
    // `getDeclaredConstructor` / `getMethod("builder")` reflection: both shapes are LMF-cached
    // per class via {@link Beans#intermediateAllocator}.
    // The allocator is already cached per class and hands back a fresh instance per call, which a
    // bean intermediate needs: telescope-row writes go through its setters, so two conversions
    // must not share one. The probe that decides whether a type has a usable strategy costs two
    // thrown exceptions for a type with neither, and it runs here rather than per conversion.
    if (beanIntermediateAllocatable(type)) return Beans.intermediateAllocator(type)::get;
    return () -> null;
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
