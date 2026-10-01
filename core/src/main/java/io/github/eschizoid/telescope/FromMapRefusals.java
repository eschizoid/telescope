package io.github.eschizoid.telescope;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    return Optional.of(raw.getName() + " is a nested object but isn't @FromMap; annotate it with @FromMap, or " + ROW);
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
   * Whether the processor generated a binder for {@code raw}, which is what {@code @FromMap} leaves
   * behind once it is gone: the annotation is source-retained. The binder is generated only for a
   * top-level type, beside it, under the type's name with {@code FromMap} appended.
   */
  private static boolean hasGeneratedBinder(final Class<?> raw) {
    if (isJdk(raw) || raw.getEnclosingClass() != null) return false;
    try {
      Class.forName(raw.getName() + "FromMap", false, raw.getClassLoader());
      return true;
    } catch (final ClassNotFoundException e) {
      return false;
    }
  }
}
