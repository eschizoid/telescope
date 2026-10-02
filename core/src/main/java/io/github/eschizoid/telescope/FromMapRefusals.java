package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.FromMapProvider;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * The declared types a {@code fromMap} component cannot be left to fill by itself: the same set the
 * binder generated for {@link io.github.eschizoid.telescope.annotations.FromMap} refuses at compile
 * time. The two paths decide this separately, the generated one over {@code javax.lang.model} types
 * and this one over reflected ones, so the order of the checks below follows the processor's.
 *
 * <p>A type outside the set has a value for a key that carries nothing: its JLS default, or an
 * empty one of the four containers. A type inside it has none the map could have supplied, so a
 * component left to it would come back {@code null} for a reason nothing reports.
 */
final class FromMapRefusals {

  private static final Set<Class<?>> BOXED = Set.of(
    Integer.class,
    Long.class,
    Double.class,
    Float.class,
    Short.class,
    Byte.class,
    Boolean.class,
    Character.class
  );

  /** Reference types a raw map plausibly carries as themselves. */
  private static final Set<Class<?>> CAST_AS_IS = Set.of(String.class, Object.class, CharSequence.class);

  /** JDK value types the generated binder rebuilds from the String form they arrive in. */
  private static final Set<Class<?>> STRING_BUILT = Set.of(
    Instant.class,
    LocalDate.class,
    LocalDateTime.class,
    LocalTime.class,
    OffsetDateTime.class,
    ZonedDateTime.class,
    Duration.class,
    Period.class,
    UUID.class,
    BigDecimal.class,
    BigInteger.class,
    URI.class,
    Currency.class,
    Locale.class,
    Pattern.class
  );

  private static final String ROW = "name it with an extract(key, accessor, converter) row";

  private FromMapRefusals() {}

  /**
   * Why {@code type} cannot be filled without a row, naming the innermost type at fault and the
   * remedy, or empty when it can.
   */
  static Optional<String> reasonFor(final Type type) {
    if (type instanceof Class<?> c) {
      if (c.isPrimitive()) return Optional.empty();
      if (c.isArray()) return unsupportedKind(type);
      return declared(c, new Type[0]);
    }
    // The reflection API reports the raw type of a parameterized type as a Class.
    if (type instanceof ParameterizedType p) return declared((Class<?>) p.getRawType(), p.getActualTypeArguments());
    return unsupportedKind(type);
  }

  private static Optional<String> declared(final Class<?> raw, final Type[] args) {
    if (BOXED.contains(raw) || raw.isEnum() || hasGeneratedBinder(raw)) return Optional.empty();
    if ((raw == List.class || raw == Set.class || raw == Optional.class) && args.length == 1) {
      return reasonFor(args[0]);
    }
    if (raw == Map.class && args.length == 2) return reasonFor(args[0]).or(() -> reasonFor(args[1]));
    if (Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
      return Optional.of(raw.getName() + " is a collection subtype; declare it as List/Set/Map/Optional, or " + ROW);
    }
    if (CAST_AS_IS.contains(raw) || STRING_BUILT.contains(raw)) return Optional.empty();
    if (isJdk(raw)) return Optional.of(raw.getName() + " can't be built from a map value; " + ROW);
    return Optional.of(
      raw.getName() +
        " has no registered @FromMap binder; annotate it with @FromMap and recompile (on the module path its" +
        " module-info must also declare \"provides " +
        FromMapProvider.class.getName() +
        " with " +
        raw.getCanonicalName() +
        "FromMap.Provider;\"), or " +
        ROW
    );
  }

  private static Optional<String> unsupportedKind(final Type type) {
    return Optional.of(
      type.getTypeName() + " can't be coerced from a map value (type variable / array / unsupported kind); " + ROW
    );
  }

  private static boolean isJdk(final Class<?> raw) {
    return raw.getName().startsWith("java.") || raw.getName().startsWith("javax.");
  }

  /**
   * Whether a generated {@code @FromMap} binder is registered for {@code raw}, which is what the
   * annotation leaves behind once it is gone: it is source-retained. The binder registers a {@link
   * FromMapProvider} naming its target, and {@link ServiceLoader} finds it in whatever compilation
   * produced it and in a native image. A class that merely shares the binder's name registers
   * nothing.
   */
  private static boolean hasGeneratedBinder(final Class<?> raw) {
    if (isJdk(raw)) return false;
    final var loader = raw.getClassLoader() != null ? raw.getClassLoader() : FromMapRefusals.class.getClassLoader();
    final Set<String> targets;
    synchronized (TARGETS_BY_LOADER) {
      targets = TARGETS_BY_LOADER.computeIfAbsent(loader, FromMapRefusals::registeredTargets);
    }
    return targets.contains(raw.getName());
  }

  /**
   * The names of the types the providers visible to {@code loader} build, read once per loader.
   * Names rather than classes, so the cache holds nothing that keeps its loader alive.
   */
  private static final Map<ClassLoader, Set<String>> TARGETS_BY_LOADER = new WeakHashMap<>();

  private static final Logger LOG = System.getLogger("io.github.eschizoid.telescope.fromMap");

  /**
   * Every target a provider visible to {@code loader} names. A registration that cannot be loaded
   * or a provider that cannot answer is skipped and logged rather than thrown: it belongs to some
   * other binder, and refusing every mapper over it would break code that never names that type.
   * The iterator moves past a provider it failed to load, so one broken entry costs only itself.
   */
  private static Set<String> registeredTargets(final ClassLoader loader) {
    final var targets = new HashSet<String>();
    final var providers = ServiceLoader.load(FromMapProvider.class, loader).iterator();
    while (true) {
      final FromMapProvider provider;
      try {
        if (!providers.hasNext()) break;
        provider = providers.next();
      } catch (final ServiceConfigurationError e) {
        LOG.log(Level.WARNING, "Skipping a @FromMap binder registration that cannot be loaded: " + e.getMessage(), e);
        continue;
      }
      try {
        targets.add(provider.targetType().getName());
      } catch (final RuntimeException | LinkageError e) {
        LOG.log(
          Level.WARNING,
          "Skipping @FromMap binder provider " + provider.getClass().getName() + ", whose targetType() failed: " + e,
          e
        );
      }
    }
    return targets;
  }
}
