package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Lens;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Probes the user's classpath for a sibling {@code <X>Telescope} metadata holder emitted by
 * {@code @Focus} / {@code @BeanFocus} / Lombok codegen ({@link
 * io.github.eschizoid.telescope.annotations.Focus Focus} / {@link
 * io.github.eschizoid.telescope.annotations.BeanFocus BeanFocus}). When present, the runtime
 * dispatch sites in {@link io.github.eschizoid.telescope.Telescope Telescope} short-circuit the
 * reflective {@link Reflective#of(Class) Reflective.of(cls)} path: a {@link
 * io.github.eschizoid.telescope.Telescope#field(io.github.eschizoid.telescope.Telescope.Accessor)
 * .field(Accessor)} call routes the {@link java.lang.invoke.SerializedLambda
 * SerializedLambda}-recovered method name to the holder's pre-baked {@code Telescope<X, FieldType>}
 * constant and pulls its {@link Lens} out directly. When absent, returns {@link Optional#empty()}
 * and today's {@link io.github.eschizoid.telescope.internal.Records#fieldLens(String)
 * Records.fieldLens(name)} / {@link io.github.eschizoid.telescope.internal.Beans#lens(Class,
 * String, io.github.eschizoid.telescope.internal.Beans.BeanWriter) Beans.lens(...)} path runs
 * unchanged.
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
   * Static-init bridge to break the otherwise-circular dependency between this {@code :internal}
   * module and {@code :api}. {@code Telescope} (in {@code :api}) registers a function that pulls
   * its package-private {@code optic} field out of a {@code Telescope} instance — invoked here in
   * {@link HolderRef#lensFor} to recover a {@link Lens} from a codegen-emitted holder constant
   * without importing {@code Telescope} at compile time.
   *
   * <p>Order is safe: every dispatch site that calls {@link #probeFor} lives inside {@code
   * Telescope}, so {@code Telescope}'s static initializer (which registers the extractor) has
   * already fired before any probe runs.
   */
  private static volatile Function<Object, Object> opticExtractor;

  /** Called once by {@code Telescope}'s static initializer to wire the bridge. */
  public static void setOpticExtractor(final Function<Object, Object> extractor) {
    opticExtractor = extractor;
  }

  /**
   * A discovered sibling {@code <X>Telescope} metadata holder for some class: the holder class
   * itself (used in diagnostics), the immutable name &rarr; constant lookup table, and a cached
   * {@link Function} bound to the holder's static {@code construct(Function<String, Object>)}
   * method. Each constant is a {@link io.github.eschizoid.telescope.Telescope Telescope} instance
   * built via {@link io.github.eschizoid.telescope.Telescope#lens(java.util.function.Function,
   * java.util.function.BiFunction) Telescope.lens(...)} at codegen time. {@link #lensFor} unwraps
   * one to a {@link Lens} for the dispatch site.
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
  ) {
    /**
     * The {@link Lens} backing the holder constant named {@code name}, or {@code null} if the
     * holder doesn't expose that name. Dispatch sites in {@code Telescope} handle the {@code null}
     * case by throwing {@link IllegalStateException} with a precise diagnostic — silent fallback
     * would mask stale codegen.
     *
     * <p>The cast routes through {@link #opticExtractor} — the static-init bridge that {@code
     * Telescope} (in {@code :api}) registers at class-load time to expose its underlying optic
     * field. This indirection keeps {@code :internal} compile-time-independent of {@code :api} (no
     * {@code Telescope} import here) while still letting holder dispatch recover a {@link Lens} at
     * runtime. If a future processor emits a wider optic ({@code Traversal}, {@code Affine}), the
     * cast surfaces as a {@code ClassCastException} at dispatch.
     */
    public Lens<?, ?> lensFor(final String name) {
      final var constant = constantsByName.get(name);
      if (constant == null) return null;
      return (Lens<?, ?>) opticExtractor.apply(constant);
    }
  }

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

  /**
   * The pre-baked {@link Lens} for property {@code name} on {@code beanOrRecord}, or {@code null}
   * if the class has no sibling {@code <X>Telescope} holder. Used by the dispatch sites on {@link
   * io.github.eschizoid.telescope.Telescope Telescope} to short-circuit the reflective {@code
   * Records.fieldLens} / {@code Beans.lens} path when the holder is present.
   *
   * <p>When the holder IS present but the requested {@code name} is missing, this method throws
   * {@link IllegalStateException} with a precise diagnostic — silent fallback would mask stale
   * codegen or accessor / component-name mismatches.
   *
   * @throws IllegalStateException when the holder is present but doesn't expose {@code name}
   */
  @SuppressWarnings("unchecked")
  public static <S, A> Lens<S, A> lensFromHolder(final Class<S> beanOrRecord, final String name) {
    final var maybeHolder = probeFor(beanOrRecord);
    if (maybeHolder.isEmpty()) return null;
    final var holder = maybeHolder.get();
    final var lens = holder.lensFor(name);
    if (lens == null) {
      throw new IllegalStateException(
        "Component '" +
          name +
          "' not found in " +
          beanOrRecord.getName() +
          "'s metadata holder (" +
          holder.holderClass().getName() +
          "). Re-run the @Focus / @BeanFocus processor."
      );
    }
    return (Lens<S, A>) lens;
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
    final java.lang.reflect.Method method;
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
    final java.lang.reflect.Method method;
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
