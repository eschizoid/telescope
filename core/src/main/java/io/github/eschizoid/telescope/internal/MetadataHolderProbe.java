package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.optics.Lens;
import io.github.eschizoid.telescope.internal.optics.Traversal;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
 */
public final class MetadataHolderProbe {

  private MetadataHolderProbe() {}

  /**
   * A discovered sibling {@code <X>Telescope} metadata holder for some class: the holder class
   * itself (used in diagnostics) plus the immutable name &rarr; constant lookup table. Each
   * constant is a {@link io.github.eschizoid.telescope.Telescope Telescope} instance built via
   * {@link io.github.eschizoid.telescope.Telescope#lens(java.util.function.Function,
   * java.util.function.BiFunction) Telescope.lens(...)} at codegen time. {@link #lensFor} unwraps
   * one to a {@link Lens} for the dispatch site.
   */
  public record HolderRef(Class<?> holderClass, Map<String, Telescope<?, ?>> constantsByName) {
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
      return Optional.of(new HolderRef(holder, Map.copyOf(constants)));
    } catch (final ClassNotFoundException e) {
      return Optional.empty();
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to probe metadata holder " + holderName, e);
    }
  }
}
