package io.github.eschizoid.telescope.internal;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
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
 * <p><b>Lookup order.</b> The probe checks two fully-qualified names against the source's
 * classloader:
 *
 * <ul>
 *   <li>{@code <Source>To<Target>Bridge} (long form, target-disambiguated) — tried first so a
 *       source carrying multiple bridges resolves unambiguously to the one targeting the requested
 *       class.
 *   <li>{@code <Source>Bridge} (short form) — accepted only when its {@code BRIDGE} field's
 *       parameterised target type matches the requested target class. The short-form name doesn't
 *       carry the target identity, so the probe verifies it reflectively before accepting.
 * </ul>
 *
 * The codegen-side contract for which shape gets emitted lives with the {@code @Bridge} annotation
 * processor; this probe describes only the runtime lookup contract.
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
   * the classpath.
   *
   * <p>Presence and {@link Optional#empty()} are memoised so repeated lookups don't repeat the
   * {@link Class#forName} call. Malformed-holder exceptions (missing {@code BRIDGE} field, wrong
   * modifiers, inaccessible field) are NOT memoised — they re-throw on every call so codegen drift
   * surfaces consistently rather than hiding behind a cached failure.
   *
   * <p>The target-mismatch silent-skip (short-form holder present, wrong target) is also cached
   * under the specific {@code (source, target)} pair — different target classes use independent
   * cache entries, so memoising the wrong-target empty for one pair doesn't shadow a correct lookup
   * for {@code (source, otherTarget)}.
   */
  public static Optional<BridgeRef> probeFor(final Class<?> sourceClass, final Class<?> targetClass) {
    return CACHE.get(sourceClass).computeIfAbsent(targetClass, t -> probe(sourceClass, t));
  }

  private static Optional<BridgeRef> probe(final Class<?> sourceClass, final Class<?> targetClass) {
    // Long-form name is target-disambiguated; try it first so multi-bridge sources resolve cleanly.
    final var longForm = sourceClass.getName() + "To" + targetClass.getSimpleName() + "Bridge";
    final var longProbe = loadBridgeClass(longForm, sourceClass.getClassLoader());
    if (longProbe.isPresent()) return Optional.of(readBridgeConstant(longProbe.get()));

    // Short-form name has no target identity baked into the FQN; verify the BRIDGE field's
    // parameterised target type matches the requested target class to avoid mis-routing
    // mapperForward(A, OtherTarget) through an A->B bridge. The target-match check returns
    // false (silent skip) only when the BRIDGE field is structurally present with a wrong
    // target — a missing or malformed field is left for readBridgeConstant to surface as a
    // precise IllegalStateException.
    final var shortForm = sourceClass.getName() + "Bridge";
    final var shortProbe = loadBridgeClass(shortForm, sourceClass.getClassLoader());
    if (shortProbe.isEmpty()) return Optional.empty();
    final var bridgeClass = shortProbe.get();
    final var match = bridgeTargetMatches(bridgeClass, targetClass);
    if (match.isPresent() && !match.get()) return Optional.empty();
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
   *       ParameterizedType} — the holder is malformed and the caller should let {@link
   *       #readBridgeConstant} surface the precise diagnostic rather than silently skip.
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
