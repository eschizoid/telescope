package io.github.eschizoid.telescope.internal;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime probe for a sibling {@code <Source>Bridge} class emitted by the {@code @Bridge}
 * annotation processor. When present, exposes the static {@code BRIDGE} constant ({@code
 * Telescope<Source, Target>}) so the public {@code mapperForward(Class, Class, …)} factory can
 * route through it directly instead of building a fresh mapper from {@code to(…)} rows.
 *
 * <p>Sibling of {@link MetadataHolderProbe} — same {@link ClassValue} caching strategy, same
 * "presence and absence both memoised" semantics. The probe runs at most once per {@code
 * (sourceClass, targetClass)} pair per classloader; subsequent {@code probeFor} calls return the
 * cached result.
 *
 * <p><b>Naming convention.</b> Two emission shapes are honoured:
 *
 * <ul>
 *   <li>User-declared single-target {@code @Bridge(value = Target.class)} → short name {@code
 *       <Source>Bridge} in the source's package.
 *   <li>Multi-target / auto-discovered pairs → long form {@code <Source>To<Target>Bridge} in the
 *       source's package — disambiguates when one source carries several {@code @Bridge}
 *       annotations.
 * </ul>
 *
 * The probe checks both — long form first (deterministic for the requested target), short form
 * second with a parameterised-type check against the requested target class.
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

  /**
   * The {@code <Source>Bridge} (or {@code <Source>To<Target>Bridge}) sibling holder for the given
   * {@code (sourceClass, targetClass)} pair, or {@link Optional#empty()} if no such holder is on
   * the classpath. Cached — both presence and absence are memoised, so repeated lookups don't
   * repeat the {@link Class#forName} call.
   */
  public static Optional<BridgeRef> probeFor(final Class<?> sourceClass, final Class<?> targetClass) {
    return CACHE.get(sourceClass).computeIfAbsent(targetClass, t -> probe(sourceClass, t));
  }

  private static Optional<BridgeRef> probe(final Class<?> sourceClass, final Class<?> targetClass) {
    // Try the long-form name first — it's deterministic for the requested target and survives
    // multi-target @Bridge declarations on the same source.
    final var longForm = sourceClass.getName() + "To" + targetClass.getSimpleName() + "Bridge";
    final var longProbe = loadBridgeClass(longForm, sourceClass.getClassLoader());
    if (longProbe.isPresent()) return Optional.of(readBridgeConstant(longProbe.get()));

    // Fall back to the short-form name — only valid when the bridge's parameterised target type
    // actually matches the requested target class, to avoid mis-routing
    // mapperForward(A, OtherTarget) through an A->B bridge.
    final var shortForm = sourceClass.getName() + "Bridge";
    final var shortProbe = loadBridgeClass(shortForm, sourceClass.getClassLoader());
    if (shortProbe.isEmpty()) return Optional.empty();
    final var bridgeClass = shortProbe.get();
    if (!bridgeTargetMatches(bridgeClass, targetClass)) return Optional.empty();
    return Optional.of(readBridgeConstant(bridgeClass));
  }

  private static Optional<Class<?>> loadBridgeClass(final String fqn, final ClassLoader loader) {
    try {
      return Optional.of(Class.forName(fqn, false, loader));
    } catch (final ClassNotFoundException e) {
      return Optional.empty();
    }
  }

  /**
   * Inspect the bridge class's {@code BRIDGE} field generic type and verify the second type
   * argument matches {@code expectedTarget}. The codegen always emits {@code Telescope<Source,
   * Target>}; reading the parameterised type's actual arguments lets us reject a short-form bridge
   * whose target doesn't match the requested mapperForward target.
   */
  private static boolean bridgeTargetMatches(final Class<?> bridgeClass, final Class<?> expectedTarget) {
    try {
      final var field = bridgeClass.getDeclaredField("BRIDGE");
      final Type generic = field.getGenericType();
      if (!(generic instanceof ParameterizedType parameterized)) return false;
      final Type[] args = parameterized.getActualTypeArguments();
      if (args.length < 2) return false;
      return args[1] instanceof Class<?> argClass && argClass.equals(expectedTarget);
    } catch (final NoSuchFieldException e) {
      return false;
    }
  }

  private static BridgeRef readBridgeConstant(final Class<?> bridgeClass) {
    try {
      final var field = bridgeClass.getDeclaredField("BRIDGE");
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
