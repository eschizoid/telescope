package io.github.eschizoid.telescope.internal.pairing;

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
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The reference types a value read from an untyped {@code Map<String, Object>} is converted into
 * without a user-supplied converter, for both the binder generated for {@code @FromMap} and the
 * runtime {@code Telescope.fromMap}. The generated binder reads the names and factory methods below
 * to write its conversion; the runtime calls the matching function. One table means a type one path
 * converts is a type the other converts, the same way.
 */
public final class MapValueTypes {

  /**
   * How a JDK value type is rebuilt from the {@code String} form it arrives in.
   *
   * @param type the value type
   * @param factory the static method taking that {@code String}, or {@code null} for the type's
   *     {@code String} constructor
   * @param build the same conversion, for the runtime
   */
  public record StringBuilt(Class<?> type, String factory, Function<String, Object> build) {}

  /** Reference types an untyped map plausibly holds as themselves, taken by a cast. */
  private static final Set<String> CAST_AS_IS = Set.of(
    "java.lang.String",
    "java.lang.Object",
    "java.lang.CharSequence"
  );

  private static final Map<String, StringBuilt> STRING_BUILT = table(
    new StringBuilt(Instant.class, "parse", Instant::parse),
    new StringBuilt(LocalDate.class, "parse", LocalDate::parse),
    new StringBuilt(LocalDateTime.class, "parse", LocalDateTime::parse),
    new StringBuilt(LocalTime.class, "parse", LocalTime::parse),
    new StringBuilt(OffsetDateTime.class, "parse", OffsetDateTime::parse),
    new StringBuilt(ZonedDateTime.class, "parse", ZonedDateTime::parse),
    new StringBuilt(Duration.class, "parse", Duration::parse),
    new StringBuilt(Period.class, "parse", Period::parse),
    new StringBuilt(UUID.class, "fromString", UUID::fromString),
    new StringBuilt(BigDecimal.class, null, BigDecimal::new),
    new StringBuilt(BigInteger.class, null, BigInteger::new),
    new StringBuilt(URI.class, "create", URI::create),
    new StringBuilt(Currency.class, "getInstance", Currency::getInstance),
    new StringBuilt(Locale.class, "forLanguageTag", Locale::forLanguageTag),
    new StringBuilt(Pattern.class, "compile", Pattern::compile)
  );

  private MapValueTypes() {}

  /** Whether the type with this qualified name is taken from the map by a cast. */
  public static boolean castAsIs(final String qualifiedName) {
    return CAST_AS_IS.contains(qualifiedName);
  }

  /** How the type with this qualified name is built from a {@code String}, if it is one of them. */
  public static Optional<StringBuilt> stringBuilt(final String qualifiedName) {
    return Optional.ofNullable(STRING_BUILT.get(qualifiedName));
  }

  /** Every type built from a {@code String}, in the order the table lists them. */
  public static Iterable<StringBuilt> allStringBuilt() {
    return STRING_BUILT.values();
  }

  private static Map<String, StringBuilt> table(final StringBuilt... entries) {
    final var byName = LinkedHashMap.<String, StringBuilt>newLinkedHashMap(entries.length);
    for (final var entry : entries) byName.put(entry.type().getName(), entry);
    return Collections.unmodifiableMap(byName);
  }
}
