package io.github.eschizoid.telescope.internal;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime probe for the {@code <Source>Bridge} class the {@code @Bridge} annotation processor emits
 * beside a source that declares one target. When present, exposes its static {@code BRIDGE}
 * constant ({@code Telescope<Source, Target>}) so a runtime mapper can convert the pair through it.
 *
 * <p>Sibling of {@link MetadataHolderProbe}: the same {@link ClassValue} caching, with presence and
 * absence both memoised, so the probe runs at most once per {@code (sourceClass, targetClass)} pair
 * per classloader.
 *
 * <p><b>Two names.</b> The short form {@code <Source>Bridge} does not carry the target, so the
 * probe checks the {@code BRIDGE} field's target type against the requested class. The long form
 * {@code <Source>To<Target>Bridge} goes both to a source declaring several targets and to a pair
 * the processor derives for a nested component with no {@code @Bridge} of its own. A derived bridge
 * takes its configuration from whichever bridges reached it, and nothing in the class says which
 * kind it is. {@link #probeFor} reads the long form first and then the short form; {@link
 * #shortFormFor} reads only the short form, for a caller that must not take a derived bridge.
 *
 * <p>The {@code bridge} field on {@link BridgeRef} is typed as {@link Object} because the optic /
 * Telescope types live in {@code :core}; consumers in {@code :core} cast it back to {@code
 * Telescope<Source, Target>}. This keeps {@code :internal} compile-time-oblivious to {@code :core}.
 */
public final class BridgeHolderProbe {

  private BridgeHolderProbe() {}

  /**
   * Reference to a discovered {@code <Source>Bridge} class and its {@code BRIDGE} constant.
   *
   * <p>{@code bridge} is the {@code public static final BRIDGE} field value — a {@code
   * Telescope<Source, Target>} from the consumer's perspective, opaque {@code Object} here. {@code
   * bridgeClass} is the holder class itself (useful for diagnostic messages).
   */
  public record BridgeRef(Class<?> bridgeClass, Object bridge) {}

  private static final ClassValue<ConcurrentHashMap<Class<?>, Optional<BridgeRef>>> CACHE = new ClassValue<>() {
    @Override
    protected ConcurrentHashMap<Class<?>, Optional<BridgeRef>> computeValue(final Class<?> type) {
      return new ConcurrentHashMap<>();
    }
  };

  private static final ClassValue<ConcurrentHashMap<Class<?>, Optional<BridgeRef>>> SHORT_FORM_CACHE =
    new ClassValue<>() {
      @Override
      protected ConcurrentHashMap<Class<?>, Optional<BridgeRef>> computeValue(final Class<?> type) {
        return new ConcurrentHashMap<>();
      }
    };

  /**
   * The {@code <Source>To<Target>Bridge} holder for {@code (sourceClass, targetClass)}, else the
   * {@code <Source>Bridge} holder, or {@link Optional#empty()} when there is neither. A long-form
   * holder may be one the processor derived, so read this only where a derived bridge is wanted.
   *
   * <p>A short-form class with no {@code BRIDGE} field is not a holder, such as a hand-written
   * {@code @ViaMapper} class, and reads as absent, as does a holder converting to another target. A
   * {@code BRIDGE} field with the wrong modifiers or a null value, and a long-form class with no
   * {@code BRIDGE} field, throw on every call rather than being memoised, so a malformed generated
   * class surfaces each time it is reached.
   */
  public static Optional<BridgeRef> probeFor(final Class<?> sourceClass, final Class<?> targetClass) {
    return memoised(CACHE, sourceClass, targetClass, true);
  }

  /** As {@link #probeFor}, reading only the short form {@code <Source>Bridge}. */
  public static Optional<BridgeRef> shortFormFor(final Class<?> sourceClass, final Class<?> targetClass) {
    return memoised(SHORT_FORM_CACHE, sourceClass, targetClass, false);
  }

  private static Optional<BridgeRef> memoised(
    final ClassValue<ConcurrentHashMap<Class<?>, Optional<BridgeRef>>> cache,
    final Class<?> sourceClass,
    final Class<?> targetClass,
    final boolean longForm
  ) {
    final var known = cache.get(sourceClass);
    final var cached = known.get(targetClass);
    if (cached != null) return cached;
    // Read outside the table's update: reading the constant initialises the holder, and that can
    // build a mapper which probes this table.
    final var found = probe(sourceClass, targetClass, longForm);
    final var raced = known.putIfAbsent(targetClass, found);
    return raced == null ? found : raced;
  }

  private static Optional<BridgeRef> probe(
    final Class<?> sourceClass,
    final Class<?> targetClass,
    final boolean longForm
  ) {
    if (longForm) {
      final var named = sourceClass.getName() + "To" + targetClass.getSimpleName() + "Bridge";
      final var holder = loadBridgeClass(named, sourceClass.getClassLoader());
      if (holder.isPresent()) return Optional.of(readBridgeConstant(holder.get()));
    }
    final var holder = loadBridgeClass(sourceClass.getName() + "Bridge", sourceClass.getClassLoader());
    if (holder.isEmpty() || !hasBridgeField(holder.get())) return Optional.empty();
    final var match = bridgeTargetMatches(holder.get(), targetClass);
    if (match.isPresent() && !match.get()) return Optional.empty();
    return Optional.of(readBridgeConstant(holder.get()));
  }

  private static boolean hasBridgeField(final Class<?> holder) {
    try {
      holder.getDeclaredField("BRIDGE");
      return true;
    } catch (final NoSuchFieldException e) {
      return false;
    }
  }

  private static Optional<Class<?>> loadBridgeClass(final String fqn, final ClassLoader loader) {
    try {
      return Optional.of(Class.forName(fqn, false, loader));
    } catch (final ClassNotFoundException e) {
      return Optional.empty();
    }
  }

  /**
   * Inspect the {@code BRIDGE} field's generic type and return whether its second type argument
   * resolves to {@code expectedTarget}.
   *
   * <p>Returns:
   *
   * <ul>
   *   <li>{@code Optional.of(true)} when the second argument equals {@code expectedTarget} as a
   *       {@link Class}, the raw of a {@link ParameterizedType}, or the component of a {@link
   *       GenericArrayType}.
   *   <li>{@code Optional.of(false)} when the field is structurally present but the second argument
   *       is some other class or type form — the bridge is real but its target doesn't match.
   *   <li>{@link Optional#empty()} when the field is absent or its generic type isn't a {@link
   *       ParameterizedType}, which leaves {@link #readBridgeConstant} to judge the field.
   * </ul>
   */
  private static Optional<Boolean> bridgeTargetMatches(final Class<?> bridgeClass, final Class<?> expectedTarget) {
    try {
      final var field = bridgeClass.getDeclaredField("BRIDGE");
      final Type generic = field.getGenericType();
      if (!(generic instanceof ParameterizedType parameterized)) return Optional.empty();
      final Type[] args = parameterized.getActualTypeArguments();
      if (args.length < 2) return Optional.empty();
      final Type arg = args[1];
      if (arg instanceof Class<?> argClass) return Optional.of(argClass.equals(expectedTarget));
      if (arg instanceof ParameterizedType inner && inner.getRawType() instanceof Class<?> rawClass) {
        return Optional.of(rawClass.equals(expectedTarget));
      }
      if (arg instanceof GenericArrayType array && array.getGenericComponentType() instanceof Class<?> component) {
        return Optional.of(component.equals(expectedTarget.getComponentType()));
      }
      return Optional.of(false);
    } catch (final NoSuchFieldException e) {
      return Optional.empty();
    }
  }

  private static BridgeRef readBridgeConstant(final Class<?> bridgeClass) {
    try {
      final var field = bridgeClass.getDeclaredField("BRIDGE");
      final var mods = field.getModifiers();
      if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods) || !Modifier.isFinal(mods)) {
        throw new IllegalStateException(
          "Bridge holder " +
            bridgeClass.getName() +
            " has a BRIDGE field but its shape is wrong (must be `public static final`). " +
            "Re-run the @Bridge processor."
        );
      }
      final var value = field.get(null);
      if (value == null) throw new IllegalStateException(
        "Bridge holder " +
          bridgeClass.getName() +
          " has a null BRIDGE constant. Re-run the @Bridge processor — the generated class is malformed."
      );
      return new BridgeRef(bridgeClass, value);
    } catch (final NoSuchFieldException e) {
      throw new IllegalStateException(
        "Bridge holder " +
          bridgeClass.getName() +
          " is missing the required `public static final BRIDGE` field. " +
          "Re-run the @Bridge processor.",
        e
      );
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
        "Cannot read BRIDGE constant on " + bridgeClass.getName() + " — field is not accessible.",
        e
      );
    }
  }
}
