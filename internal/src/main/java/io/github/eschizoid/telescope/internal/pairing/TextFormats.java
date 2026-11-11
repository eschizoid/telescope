package io.github.eschizoid.telescope.internal.pairing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParsePosition;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The shared decision for the rows that write a value as text through a pattern and read it back:
 * {@code toFormatted} and {@code toFormattedOneWay} for a date or a time, through a {@link
 * DateTimeFormatter}, and {@code toFormattedNumber} and {@code toFormattedNumberOneWay} for a
 * number, through a {@link DecimalFormat}. The runtime refuses a row when the mapper is built, and
 * the processor and the verifier refuse a row whose pattern is a constant at compile time, each
 * with the message this class gives, so all three accept and refuse the same rows.
 *
 * <p>A pattern is accepted when the JDK compiles it, when it formats each of a set of probe values
 * of the property's class, and, for a row that also converts back, when the text it writes for each
 * probe reads back as that probe. A date or a time is probed across three centuries, across months
 * whose English names share a first letter, and either side of noon, in {@link Locale#ROOT} and in
 * English and, where the row is given a zone, in UTC, so the decision is the same on every machine;
 * a pattern writing a two-digit year or a one-letter month reads some of them back as another
 * value. A probe reads back cut to the precision the pattern writes: a pattern writing minutes
 * reads back a time with no seconds. A number is probed with whole values and, for a class that
 * holds a fraction, with values of no more fraction digits than the pattern writes, so a pattern in
 * scientific notation that writes fewer digits than a value has is refused. Which zone or locale a
 * row is given does not change which fields the formatter can read.
 *
 * <p>A number is read back with {@link DecimalFormat#setParseBigDecimal} on, and the whole text
 * must be read. An integral class, {@code BigInteger} and {@code BigDecimal} take the parsed {@code
 * BigDecimal} only where it converts exactly ({@link #exactConversion}); a {@code float} or {@code
 * double} takes the nearest value.
 */
public final class TextFormats {

  private TextFormats() {}

  /** The row that formats a date or a time both ways. */
  public static final String TO_FORMATTED = "toFormatted";

  /** The row that formats a date or a time forward only. */
  public static final String TO_FORMATTED_ONE_WAY = "toFormattedOneWay";

  /** The row that formats a number both ways. */
  public static final String TO_FORMATTED_NUMBER = "toFormattedNumber";

  /** The row that formats a number forward only. */
  public static final String TO_FORMATTED_NUMBER_ONE_WAY = "toFormattedNumberOneWay";

  private static final Set<String> ROWS = Set.of(
    TO_FORMATTED,
    TO_FORMATTED_ONE_WAY,
    TO_FORMATTED_NUMBER,
    TO_FORMATTED_NUMBER_ONE_WAY
  );

  /** Whether {@code row} names one of the four formatted rows. */
  public static boolean isFormattedRow(final String row) {
    return ROWS.contains(row);
  }

  /** Whether the formatted row {@code row} formats a number, rather than a date or a time. */
  public static boolean formatsNumber(final String row) {
    return row.equals(TO_FORMATTED_NUMBER) || row.equals(TO_FORMATTED_NUMBER_ONE_WAY);
  }

  /** Whether the formatted row {@code row} converts forward only. */
  public static boolean oneWay(final String row) {
    return row.equals(TO_FORMATTED_ONE_WAY) || row.equals(TO_FORMATTED_NUMBER_ONE_WAY);
  }

  /**
   * The values a date and time pattern is probed with, as a {@code LocalDateTime} each class takes
   * its part of: years in three centuries, months whose English names share a first letter, hours
   * either side of noon, and a time below the second. The first is the value whose formatting
   * decides whether the pattern names only fields the class has.
   */
  private static final List<LocalDateTime> TEMPORAL_PROBES = List.of(
    LocalDateTime.of(2001, 2, 3, 4, 5, 6, 7_008_009),
    LocalDateTime.of(1899, 12, 31, 23, 59, 58, 500_000_000),
    LocalDateTime.of(1999, 1, 1, 0, 0),
    LocalDateTime.of(2026, 6, 3, 13, 45, 30),
    LocalDateTime.of(2150, 7, 15, 12, 0),
    LocalDateTime.of(1970, 3, 28, 18, 30),
    LocalDateTime.of(2050, 8, 9, 9, 9, 9),
    LocalDateTime.of(2008, 5, 17, 21, 10),
    LocalDateTime.of(1988, 4, 10, 11, 11)
  );

  /** The part of a probe each date and time class takes, in {@code offset}, by binary name. */
  private static final Map<String, BiFunction<LocalDateTime, ZoneOffset, TemporalAccessor>> TEMPORAL_PARTS =
    Map.ofEntries(
      Map.entry(LocalDate.class.getName(), (probe, offset) -> probe.toLocalDate()),
      Map.entry(LocalTime.class.getName(), (probe, offset) -> probe.toLocalTime()),
      Map.entry(LocalDateTime.class.getName(), (probe, offset) -> probe),
      Map.entry(OffsetDateTime.class.getName(), (probe, offset) -> probe.atOffset(offset)),
      Map.entry(OffsetTime.class.getName(), (probe, offset) -> probe.toLocalTime().atOffset(offset)),
      Map.entry(ZonedDateTime.class.getName(), (probe, offset) -> probe.atZone(offset)),
      Map.entry(Instant.class.getName(), (probe, offset) -> probe.toInstant(offset)),
      Map.entry(Year.class.getName(), (probe, offset) -> Year.from(probe)),
      Map.entry(YearMonth.class.getName(), (probe, offset) -> YearMonth.from(probe)),
      Map.entry(MonthDay.class.getName(), (probe, offset) -> MonthDay.from(probe))
    );

  /** How a value of each date and time class is read from what a formatter parsed. */
  private static final Map<String, TemporalQuery<?>> TEMPORAL_QUERIES = Map.ofEntries(
    Map.entry(LocalDate.class.getName(), LocalDate::from),
    Map.entry(LocalTime.class.getName(), LocalTime::from),
    Map.entry(LocalDateTime.class.getName(), LocalDateTime::from),
    Map.entry(OffsetDateTime.class.getName(), OffsetDateTime::from),
    Map.entry(OffsetTime.class.getName(), OffsetTime::from),
    Map.entry(ZonedDateTime.class.getName(), ZonedDateTime::from),
    Map.entry(Instant.class.getName(), Instant::from),
    Map.entry(Year.class.getName(), Year::from),
    Map.entry(YearMonth.class.getName(), YearMonth::from),
    Map.entry(MonthDay.class.getName(), MonthDay::from)
  );

  /**
   * The units below the hour a probe is cut to, finest first, to find the precision a pattern
   * writes.
   */
  private static final List<ChronoUnit> CUTS = List.of(
    ChronoUnit.MICROS,
    ChronoUnit.MILLIS,
    ChronoUnit.SECONDS,
    ChronoUnit.MINUTES,
    ChronoUnit.HOURS
  );

  /** The date and time classes a formatted row takes, by simple name, as a refusal lists them. */
  private static final String TEMPORAL_NAMES =
    "LocalDate, LocalTime, LocalDateTime, OffsetDateTime, OffsetTime, ZonedDateTime, Instant, Year," +
    " YearMonth and MonthDay";

  /** Whether {@code typeName}, a binary name, is a date or time class a formatted row takes. */
  public static boolean formatsTemporal(final String typeName) {
    return TEMPORAL_PARTS.containsKey(typeName);
  }

  /**
   * The query reading a value of {@code typeName}, a date or time class a formatted row takes, from
   * what a formatter parsed: the class's own {@code from}.
   */
  public static TemporalQuery<?> temporalQuery(final String typeName) {
    return TEMPORAL_QUERIES.get(typeName);
  }

  /**
   * The number {@code typeName} holds, by the binary name of its class or its wrapper's, or null
   * where it is no number a formatted row takes.
   */
  public static Scalar numberOf(final String typeName) {
    final var scalar = Scalar.ofClass(typeName);
    return scalar == null || scalar == Scalar.BOOLEAN || scalar == Scalar.CHAR || scalar == Scalar.STRING
      ? null
      : scalar;
  }

  /**
   * The {@code BigDecimal} method converting the value a number pattern parsed into {@code scalar}
   * without losing any of it, or null for {@code BigDecimal} itself, which takes it as it is, and
   * for a {@code float} or a {@code double}, which take the nearest value through {@link
   * #inexactConversion}.
   */
  public static String exactConversion(final Scalar scalar) {
    return switch (scalar) {
      case BYTE -> "byteValueExact";
      case SHORT -> "shortValueExact";
      case INT -> "intValueExact";
      case LONG -> "longValueExact";
      case BIG_INTEGER -> "toBigIntegerExact";
      default -> null;
    };
  }

  /**
   * The {@code Number} method converting the value a number pattern parsed into {@code scalar}, a
   * {@code float} or a {@code double}, at the nearest value; null for every other number.
   */
  public static String inexactConversion(final Scalar scalar) {
    return switch (scalar) {
      case FLOAT -> "floatValue";
      case DOUBLE -> "doubleValue";
      default -> null;
    };
  }

  /**
   * Why the formatted row {@code row} cannot format the property {@code property}, whose values are
   * of the class {@code typeName}, a binary name and the wrapper's for a primitive, through {@code
   * pattern}; or null when it can. {@code shownType} is how a message names the property's type.
   * {@code zoned} says the row is given a zone, which only the date and time rows take.
   */
  public static String refusal(
    final String row,
    final String property,
    final String typeName,
    final String shownType,
    final String pattern,
    final boolean zoned
  ) {
    return formatsNumber(row)
      ? numberRefusal(row, property, typeName, shownType, pattern)
      : temporalRefusal(row, property, typeName, shownType, pattern, zoned);
  }

  private static String temporalRefusal(
    final String row,
    final String property,
    final String typeName,
    final String shownType,
    final String pattern,
    final boolean zoned
  ) {
    final var part = TEMPORAL_PARTS.get(typeName);
    if (part == null) return PairingMessages.formatNotATemporal(row, property, shownType, TEMPORAL_NAMES);
    final var offset = zoned ? ZoneOffset.UTC : ZoneOffset.ofHours(1);
    for (final var locale : List.of(Locale.ROOT, Locale.ENGLISH)) {
      final DateTimeFormatter formatter;
      try {
        final var compiled = DateTimeFormatter.ofPattern(pattern, locale);
        formatter = zoned ? compiled.withZone(ZoneOffset.UTC) : compiled;
      } catch (final IllegalArgumentException e) {
        return PairingMessages.formatPatternInvalid(row, property, pattern, "date and time", e.getMessage());
      }
      for (final var probe : TEMPORAL_PROBES) {
        final var value = part.apply(probe, offset);
        final String text;
        try {
          text = formatter.format(value);
        } catch (final DateTimeException e) {
          if (!zoned && value instanceof Instant) return PairingMessages.formatNeedsZone(row, property, pattern);
          return PairingMessages.formatPatternMissesField(row, property, pattern, shownType, e.getMessage());
        }
        if (oneWay(row)) continue;
        final Object back;
        try {
          back = formatter.parse(text, TEMPORAL_QUERIES.get(typeName));
        } catch (final DateTimeException e) {
          return PairingMessages.formatDoesNotReadBack(row, property, pattern, shownType, text, e.getMessage());
        }
        final var written = writtenPart(value, formatter, text);
        if (!back.equals(written)) {
          return PairingMessages.formatReadsBackAs(row, property, pattern, written.toString(), text, back.toString());
        }
      }
    }
    return null;
  }

  /**
   * The part of {@code value} the pattern writes as {@code text}: the value cut to the coarsest
   * unit below the hour that leaves the text the same, which is the value the text reads back as
   * when the pattern reads what it writes. A pattern writing minutes reads back a time with no
   * seconds.
   */
  private static TemporalAccessor writtenPart(
    final TemporalAccessor value,
    final DateTimeFormatter formatter,
    final String text
  ) {
    var kept = value;
    for (final var unit : CUTS) {
      final var cut = cut(value, unit);
      if (cut == null || !formatter.format(cut).equals(text)) return kept;
      kept = cut;
    }
    return kept;
  }

  /** {@code value} cut to {@code unit}, or null for a class with no time of day. */
  private static TemporalAccessor cut(final TemporalAccessor value, final ChronoUnit unit) {
    return switch (value) {
      case LocalTime time -> time.truncatedTo(unit);
      case LocalDateTime time -> time.truncatedTo(unit);
      case OffsetDateTime time -> time.truncatedTo(unit);
      case OffsetTime time -> time.truncatedTo(unit);
      case ZonedDateTime time -> time.truncatedTo(unit);
      case Instant time -> time.truncatedTo(unit);
      default -> null;
    };
  }

  /**
   * The integral values a number pattern is probed with, each read in every class that holds it.
   */
  private static final List<BigDecimal> WHOLE_PROBES = List.of(
    BigDecimal.ZERO,
    BigDecimal.valueOf(7),
    BigDecimal.valueOf(-42),
    BigDecimal.valueOf(127),
    BigDecimal.valueOf(12_345),
    BigDecimal.valueOf(1_234_567),
    BigDecimal.valueOf(987_654_321)
  );

  /**
   * The fractional values a number pattern is probed with for a class that holds a fraction, each
   * cut to the fraction digits the pattern writes.
   */
  private static final List<BigDecimal> FRACTION_PROBES = List.of(
    new BigDecimal("12.5"),
    new BigDecimal("-0.25"),
    new BigDecimal("3.125"),
    new BigDecimal("1234567.891")
  );

  private static String numberRefusal(
    final String row,
    final String property,
    final String typeName,
    final String shownType,
    final String pattern
  ) {
    final var scalar = numberOf(typeName);
    if (scalar == null) return PairingMessages.formatNotANumber(row, property, shownType);
    final DecimalFormat format;
    try {
      format = numberFormat(pattern, Locale.ROOT);
    } catch (final IllegalArgumentException e) {
      return PairingMessages.formatPatternInvalid(row, property, pattern, "number", e.getMessage());
    }
    if (oneWay(row)) return null;
    final var limit = switch (scalar) {
      case BYTE -> BigDecimal.valueOf(Byte.MAX_VALUE);
      case SHORT -> BigDecimal.valueOf(Short.MAX_VALUE);
      default -> null;
    };
    final var probes = new ArrayList<BigDecimal>();
    for (final var probe : WHOLE_PROBES) {
      if (limit == null || probe.abs().compareTo(limit) <= 0) probes.add(probe);
    }
    if (exactConversion(scalar) == null) {
      final var digits = Math.min(format.getMaximumFractionDigits(), 6);
      for (final var probe : FRACTION_PROBES) {
        final var cut = probe.setScale(Math.min(probe.scale(), digits), RoundingMode.DOWN);
        if (!probes.contains(cut)) probes.add(cut);
      }
    }
    for (final var probe : probes) {
      final var text = format.format(probe);
      final var position = new ParsePosition(0);
      final var parsed = format.parse(text, position);
      if (parsed == null || position.getIndex() != text.length()) {
        return PairingMessages.formatDoesNotReadBack(
          row,
          property,
          pattern,
          shownType,
          text,
          "the pattern does not read the text it writes"
        );
      }
      if (!(parsed instanceof BigDecimal exact) || exact.compareTo(probe) != 0) {
        return PairingMessages.formatReadsBackAs(
          row,
          property,
          pattern,
          probe.toPlainString(),
          text,
          parsed.toString()
        );
      }
    }
    return null;
  }

  /**
   * The {@code DecimalFormat} a number row formats and parses through: {@code pattern} with the
   * symbols of {@code locale}, parsing into a {@code BigDecimal}.
   *
   * @throws IllegalArgumentException where {@code pattern} is no number pattern
   */
  public static DecimalFormat numberFormat(final String pattern, final Locale locale) {
    final var format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale));
    format.setParseBigDecimal(true);
    return format;
  }
}
