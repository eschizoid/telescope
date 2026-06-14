package io.github.eschizoid.telescope.internal;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Probes the user's classpath for a sibling {@code <X>Telescope} metadata holder emitted by
 * {@code @Focus} / {@code @BeanFocus} / Lombok codegen ({@code @Focus} / {@code @BeanFocus}). When
 * present, the runtime dispatch sites in {@code Telescope} short-circuit the reflective {@link
 * Reflective#of(Class) Reflective.of(cls)} path: a {@code Telescope.field} call routes the {@link
 * java.lang.invoke.SerializedLambda SerializedLambda}-recovered method name to the holder's
 * pre-baked {@code Telescope<X, FieldType>} constant and pulls its {@link
 * io.github.eschizoid.telescope.internal.optics.Lens Lens} out directly. When absent, returns
 * {@link Optional#empty()} and today's {@link
 * io.github.eschizoid.telescope.internal.Records#fieldLens(String) Records.fieldLens(name)} /
 * {@link io.github.eschizoid.telescope.internal.Beans#lens(Class, String,
 * io.github.eschizoid.telescope.internal.Beans.BeanWriter) Beans.lens(...)} path runs unchanged.
 *
 * <p>Cached behind {@link ClassValue} — one probe per class for the classloader's lifetime. The
 * absent case is cached too, so a non-annotated class doesn't repeat the {@link Class#forName}
 * miss.
 *
 * <p>The {@code <X>Telescope} class is a top-level {@code public final} utility in the user's
 * package; the constants are {@code public static final}. The holder is plain public Java — no
 * {@code privateLookupIn} required, and the JPMS {@code opens} directive that the LMF substrate
 * needs for non-annotated types is not required for the probe itself.
 *
 * <p>Holders also expose a {@code public static <X> construct(Function<String, Object> values)}
 * method that mirrors the {@code <X>Path<R>}'s write strategy (canonical constructor for records;
 * builder chain or no-arg ctor + setters for beans). Probing binds the static method via {@link
 * LambdaMetafactory} into a cached {@code Function<Function<String, Object>, Object>} on {@link
 * HolderRef}. {@link io.github.eschizoid.telescope.internal.Reflective#structuralIso
 * Reflective.structuralIso}'s forward branch routes through it when present, skipping the
 * reflective {@link io.github.eschizoid.telescope.internal.Records#construct Records.construct} /
 * {@link io.github.eschizoid.telescope.internal.Beans.BeanWriter Beans.BeanWriter} path. A holder
 * that lacks the required {@code construct} method (out-of-date codegen on the classpath) trips a
 * precise {@link IllegalStateException} at probe time rather than silently falling back.
 */
public final class MetadataHolderProbe {

  private MetadataHolderProbe() {}

  /**
   * A discovered sibling {@code <X>Telescope} metadata holder for some class: the holder class
   * itself (used in diagnostics), the immutable name &rarr; constant lookup table, and a cached
   * {@link Function} bound to the holder's static {@code construct(Function<String, Object>)}
   * method.
   *
   * <p>Each value in {@code constantsByName} is a {@code Telescope} instance built via {@code
   * Telescope.lens} at codegen time, typed as {@link Object} here because {@code :internal} does
   * not see {@code Telescope}. The caller in {@code :core} casts to {@code Telescope} and reads its
   * underlying optic — keeping the cast on the side of the module that owns the type. No callback,
   * no global state, no static-init bridge.
   *
   * <p>The {@code constructor} field is always non-{@code null} when the holder is present — the
   * probe throws {@link IllegalStateException} if the holder is missing the required {@code
   * construct(Function)} method, so codegen drift surfaces as a precise diagnostic rather than a
   * silent fallback.
   */
  public record HolderRef(
    Class<?> holderClass,
    Map<String, Object> constantsByName,
    Function<Function<String, Object>, Object> constructor
  ) {}

  private static final ClassValue<Optional<HolderRef>> CACHE = new ClassValue<>() {
    @Override
    protected Optional<HolderRef> computeValue(final Class<?> type) {
      return probe(type);
    }
  };

  /**
   * The {@code <X>Telescope} sibling holder for {@code beanOrRecord}, or {@link Optional#empty()}
   * if no such holder is on the classpath. Cached — both presence and absence are memoised, so
   * repeated lookups don't repeat the {@link Class#forName} call.
   */
  public static Optional<HolderRef> probeFor(final Class<?> beanOrRecord) {
    return CACHE.get(beanOrRecord);
  }

  private static Optional<HolderRef> probe(final Class<?> cls) {
    final var holderName = cls.getName() + "FieldOptics";
    try {
      final var holder = Class.forName(holderName, false, cls.getClassLoader());
      final var constants = readConstants(holder);
      final var constructor = bindConstructor(holder, cls);
      return Optional.of(new HolderRef(holder, constants, constructor));
    } catch (final ClassNotFoundException e) {
      return Optional.empty();
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to probe metadata holder " + holderName, e);
    }
  }

  /**
   * Read the holder's {@code public static Map<String, Object> constants()} method — one {@link
   * Class#getDeclaredMethod} + one {@link java.lang.reflect.Method#invoke} regardless of holder
   * size. The holder is required to expose this method; if it's missing or shaped wrong, the
   * codegen on the classpath is out of date with this runtime.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> readConstants(final Class<?> holder) throws ReflectiveOperationException {
    final Method method;
    try {
      method = holder.getDeclaredMethod("constants");
    } catch (final NoSuchMethodException e) {
      throw new IllegalStateException(
        "Metadata holder " +
          holder.getName() +
          " is missing the required `public static Map<String, Object> constants()` method. " +
          "Re-run the @Focus / @BeanFocus processor.",
        e
      );
    }
    final var mods = method.getModifiers();
    if (!Modifier.isStatic(mods) || !Modifier.isPublic(mods) || !Map.class.isAssignableFrom(method.getReturnType())) {
      throw new IllegalStateException(
        "Metadata holder " +
          holder.getName() +
          " has a `constants()` method but its shape is wrong (must be `public static Map<...>`). " +
          "Re-run the @Focus / @BeanFocus processor."
      );
    }
    final var result = (Map<String, Object>) method.invoke(null);
    return result == null ? Map.of() : Map.copyOf(result);
  }

  /**
   * Bind the holder's {@code public static <X> construct(Function<String, Object> values)} method
   * to a cached {@link Function} via {@link LambdaMetafactory}, so the runtime forward branch in
   * {@link Reflective#structuralIso} can invoke it directly without per-call reflection. The holder
   * is required to expose this method; if it's missing or shaped wrong, the codegen on the
   * classpath is out of date with this runtime.
   *
   * <p>The holder is plain public Java in the user's package, so a default {@link
   * MethodHandles#lookup} suffices; no {@code privateLookupIn} dance, no JPMS {@code opens}
   * requirement beyond what the holder's package already grants.
   */
  @SuppressWarnings("unchecked")
  private static Function<Function<String, Object>, Object> bindConstructor(
    final Class<?> holder,
    final Class<?> target
  ) {
    final Method method;
    try {
      method = holder.getDeclaredMethod("construct", Function.class);
    } catch (final NoSuchMethodException e) {
      throw new IllegalStateException(
        "Metadata holder " +
          holder.getName() +
          " is missing the required `public static " +
          target.getSimpleName() +
          " construct(Function<String, Object>)` method. Re-run the @Focus / @BeanFocus processor.",
        e
      );
    }
    if (
      !Modifier.isPublic(method.getModifiers()) ||
      !Modifier.isStatic(method.getModifiers()) ||
      !target.isAssignableFrom(method.getReturnType())
    ) {
      throw new IllegalStateException(
        "Metadata holder " +
          holder.getName() +
          " has a `construct(Function)` method but its shape is wrong (must be `public static " +
          target.getSimpleName() +
          " construct(Function<String, Object>)`). Re-run the @Focus / @BeanFocus processor."
      );
    }
    try {
      final var lookup = MethodHandles.lookup();
      final var handle = lookup.unreflect(method);
      final var callSite = LambdaMetafactory.metafactory(
        lookup,
        "apply",
        MethodType.methodType(Function.class),
        MethodType.methodType(Object.class, Object.class),
        handle,
        MethodType.methodType(method.getReturnType(), Function.class)
      );
      return (Function<Function<String, Object>, Object>) callSite.getTarget().invoke();
    } catch (final Throwable t) {
      throw new IllegalStateException("Failed to bind construct(Function) on holder " + holder.getName(), t);
    }
  }
}
