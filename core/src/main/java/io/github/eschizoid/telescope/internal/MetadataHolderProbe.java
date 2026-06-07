package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.optics.Lens;
import io.github.eschizoid.telescope.internal.optics.Traversal;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Probes the user's classpath for a sibling {@code <X>Telescope} metadata holder emitted by
 * {@code @Focus} / {@code @BeanFocus} / Lombok codegen ({@link
 * io.github.eschizoid.telescope.annotations.Focus Focus} / {@link
 * io.github.eschizoid.telescope.annotations.BeanFocus BeanFocus}) under ADR-0006. When present, the
 * runtime dispatch sites in {@link io.github.eschizoid.telescope.Telescope Telescope} short-circuit
 * the reflective {@link Reflective#of(Class) Reflective.of(cls)} path: a {@link
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
 * <p><b>ADR-0006 Phase D.</b> Holders also expose a {@code public static <X>
 * construct(Function<String, Object> values)} method that mirrors the {@code <X>Path<R>}'s write
 * strategy (canonical constructor for records; builder chain or no-arg ctor + setters for beans).
 * Probing binds the static method via {@link LambdaMetafactory} into a cached {@code
 * Function<Function<String, Object>, Object>} on {@link HolderRef}. {@link
 * io.github.eschizoid.telescope.internal.Reflective#structuralIso Reflective.structuralIso}'s
 * forward branch routes through it when present, skipping the reflective {@link
 * io.github.eschizoid.telescope.internal.Records#construct Records.construct} / {@link
 * io.github.eschizoid.telescope.internal.Beans.BeanWriter Beans.BeanWriter} path. Older holders
 * that predate Phase D (no {@code construct} method) degrade gracefully — the constructor field is
 * {@code null} and the reflective path runs.
 */
public final class MetadataHolderProbe {

  private MetadataHolderProbe() {}

  /**
   * A discovered sibling {@code <X>Telescope} metadata holder for some class: the holder class
   * itself (used in diagnostics), the immutable name &rarr; constant lookup table, and — when the
   * holder was emitted with ADR-0006 Phase D — a cached {@link Function} bound to the holder's
   * static {@code construct(Function<String, Object>)} method. Each constant is a {@link
   * io.github.eschizoid.telescope.Telescope Telescope} instance built via {@link
   * io.github.eschizoid.telescope.Telescope#lens(java.util.function.Function,
   * java.util.function.BiFunction) Telescope.lens(...)} at codegen time. {@link #lensFor} unwraps
   * one to a {@link Lens} for the dispatch site.
   *
   * <p>The {@code constructor} field is {@code null} when the holder doesn't expose a {@code
   * construct} method — older Phase A holders, or future processors that opt out. {@link
   * io.github.eschizoid.telescope.internal.Reflective#structuralIso Reflective.structuralIso}
   * checks for {@code null} and falls back to the reflective constructor path.
   */
  public record HolderRef(
    Class<?> holderClass,
    Map<String, Telescope<?, ?>> constantsByName,
    Function<Function<String, Object>, Object> constructor
  ) {
    /**
     * The {@link Lens} backing the holder constant named {@code name}, or {@code null} if the
     * holder doesn't expose that name. Dispatch sites in {@link
     * io.github.eschizoid.telescope.Telescope Telescope} handle the {@code null} case by throwing
     * {@link IllegalStateException} with the ADR-0006 §9 diagnostic — silent fallback would mask
     * stale codegen.
     */
    public Lens<?, ?> lensFor(final String name) {
      final var constant = constantsByName.get(name);
      if (constant == null) return null;
      final Traversal<?, ?> optic = constant.optic();
      // The holder constants are emitted via Telescope.lens(getter, setter), which wraps a Lens —
      // see FocusProcessor#emitMetadataHolder / Telescope.lens. If a future processor emits a
      // wider optic (Traversal, Affine), this cast would surface as ClassCastException at the
      // dispatch site; the cast is the load-bearing assumption the design rests on.
      return (Lens<?, ?>) optic;
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
   * <p><b>ADR-0006 §9:</b> when the holder IS present but the requested {@code name} is missing,
   * this method throws {@link IllegalStateException} with a precise diagnostic — silent fallback
   * would mask stale codegen or accessor / component-name mismatches.
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
    final var holderName = cls.getName() + "Telescope";
    try {
      final var holder = Class.forName(holderName, false, cls.getClassLoader());
      final Map<String, Telescope<?, ?>> constants = new LinkedHashMap<>();
      for (final var field : holder.getDeclaredFields()) {
        final var mods = field.getModifiers();
        if (
          !Modifier.isStatic(mods) ||
          !Modifier.isFinal(mods) ||
          !Modifier.isPublic(mods) ||
          !Telescope.class.isAssignableFrom(field.getType())
        ) {
          continue;
        }
        final var value = field.get(null);
        if (value instanceof Telescope<?, ?> t) constants.put(field.getName(), t);
      }
      final var constructor = bindConstructor(holder, cls);
      return Optional.of(new HolderRef(holder, Map.copyOf(constants), constructor));
    } catch (final ClassNotFoundException e) {
      return Optional.empty();
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to probe metadata holder " + holderName, e);
    }
  }

  /**
   * Bind the holder's {@code public static <X> construct(Function<String, Object> values)} method
   * to a cached {@link Function} via {@link LambdaMetafactory}, so the runtime forward branch in
   * {@link Reflective#structuralIso} can invoke it directly without per-call reflection. Returns
   * {@code null} when the holder doesn't expose a matching {@code construct} method (older Phase A
   * holders that predate Phase D, or future opt-out paths) — callers fall back to the reflective
   * {@link Reflective#construct} path.
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
    try {
      final var method = holder.getDeclaredMethod("construct", Function.class);
      if (
        !Modifier.isPublic(method.getModifiers()) ||
        !Modifier.isStatic(method.getModifiers()) ||
        !target.isAssignableFrom(method.getReturnType())
      ) {
        return null;
      }
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
    } catch (final NoSuchMethodException e) {
      return null;
    } catch (final Throwable t) {
      throw new IllegalStateException("Failed to bind construct(Function) on holder " + holder.getName(), t);
    }
  }
}
