package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.text.ParsePosition;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The shared decision for the rows that format a value through a pattern. */
class TextFormatsTest {

  private static String date(final String row, final String type, final String pattern, final boolean zoned) {
    return TextFormats.refusal(row, "p", type, type.substring(type.lastIndexOf('.') + 1), pattern, zoned);
  }

  private static String number(final String row, final String type, final String shown, final String pattern) {
    return TextFormats.refusal(row, "p", type, shown, pattern, false);
  }

  @Nested
  @DisplayName("the rows")
  class Rows {

    @Test
    @DisplayName("four rows format, two of them numbers and two of them forward only")
    void theRowsAreNamed() {
      for (final var row : List.of(
        "toFormatted",
        "toFormattedOneWay",
        "toFormattedNumber",
        "toFormattedNumberOneWay"
      )) {
        assertTrue(TextFormats.isFormattedRow(row), row);
      }
      assertFalse(TextFormats.isFormattedRow("toOneWay"));
      assertTrue(TextFormats.formatsNumber("toFormattedNumberOneWay"));
      assertFalse(TextFormats.formatsNumber("toFormattedOneWay"));
      assertTrue(TextFormats.oneWay("toFormattedOneWay"));
      assertFalse(TextFormats.oneWay("toFormattedNumber"));
    }
  }

  @Nested
  @DisplayName("dates and times")
  class Temporal {

    @Test
    @DisplayName("a pattern that formats and reads back a value of the class is accepted")
    void anAcceptedPattern() {
      assertNull(date("toFormatted", "java.time.LocalDate", "dd.MM.yyyy", false));
      assertNull(date("toFormatted", "java.time.MonthDay", "dd.MM", false));
      assertNull(date("toFormatted", "java.time.Instant", "yyyy-MM-dd HH:mm", true));
      assertTrue(TextFormats.formatsTemporal("java.time.ZonedDateTime"));
      assertFalse(TextFormats.formatsTemporal("java.time.DayOfWeek"));
    }

    @Test
    @DisplayName("each query reads its own class back from what a formatter parsed")
    void theQueryReadsTheClass() {
      final var parsed = DateTimeFormatter.ofPattern("dd.MM").parse("03.02");
      assertEquals(MonthDay.of(2, 3), TextFormats.temporalQuery("java.time.MonthDay").queryFrom(parsed));
      final var day = DateTimeFormatter.ISO_LOCAL_DATE.parse("2001-02-03");
      assertEquals(LocalDate.of(2001, 2, 3), TextFormats.temporalQuery("java.time.LocalDate").queryFrom(day));
    }

    @Test
    @DisplayName("a class the rows do not format is refused, listing the ones they do")
    void anUnformattedClass() {
      assertEquals(
        PairingMessages.formatNotATemporal(
          "toFormatted",
          "p",
          "DayOfWeek",
          "LocalDate, LocalTime, LocalDateTime, OffsetDateTime, OffsetTime, ZonedDateTime, Instant, Year," +
            " YearMonth and MonthDay"
        ),
        date("toFormatted", "java.time.DayOfWeek", "EEEE", false)
      );
    }

    @Test
    @DisplayName("an invalid pattern, a missing field and an unzoned Instant are each refused in their own words")
    void eachRefusal() {
      assertEquals(
        PairingMessages.formatPatternInvalid(
          "toFormatted",
          "p",
          "yyyy{",
          "date and time",
          "Pattern includes reserved character: '{'"
        ),
        date("toFormatted", "java.time.LocalDate", "yyyy{", false)
      );
      assertTrue(
        date("toFormatted", "java.time.LocalDate", "HH", false).contains("names a field a LocalDate does not have"),
        "missing field"
      );
      assertEquals(
        PairingMessages.formatNeedsZone("toFormattedOneWay", "p", "yyyy"),
        date("toFormattedOneWay", "java.time.Instant", "yyyy", false)
      );
    }

    @Test
    @DisplayName("a pattern that does not read back is refused both ways and accepted forward only")
    void aPatternThatDoesNotReadBack() {
      final var refusal = date("toFormatted", "java.time.LocalDate", "MM/yyyy", false);
      assertTrue(
        refusal.startsWith(
          "Mapping.toFormatted(...) on 'p': the text the pattern \"MM/yyyy\" writes, such as" +
            " \"02/2001\", does not read back as a LocalDate"
        ),
        refusal
      );
      assertTrue(refusal.endsWith("or use toFormattedOneWay on a forward-only mapper."), refusal);
      assertNull(date("toFormattedOneWay", "java.time.LocalDate", "MM/yyyy", false));
      // A zone read in the pattern's place is what lets an Instant read back from a date and time.
      assertTrue(date("toFormatted", "java.time.Instant", "yyyy-MM-dd", true).contains("does not read back"));
    }

    @Test
    @DisplayName("a two-digit year or a one-letter month reads another value back, and is refused both ways")
    void aLossyPatternIsRefused() {
      assertEquals(
        PairingMessages.formatReadsBackAs("toFormatted", "p", "yy-MM-dd", "1899-12-31", "99-12-31", "2099-12-31"),
        date("toFormatted", "java.time.LocalDate", "yy-MM-dd", false)
      );
      assertEquals(
        PairingMessages.formatReadsBackAs("toFormatted", "p", "dd MMMMM yyyy", "1999-01-01", "01 J 1999", "1999-07-01"),
        date("toFormatted", "java.time.LocalDate", "dd MMMMM yyyy", false)
      );
      assertNull(date("toFormattedOneWay", "java.time.LocalDate", "yy-MM-dd", false));
      assertNull(date("toFormattedOneWay", "java.time.LocalDate", "dd MMMMM yyyy", false));
    }

    @Test
    @DisplayName("a pattern writing less than the whole time reads back the time it writes, and is accepted")
    void aCoarserTimeIsAccepted() {
      assertNull(date("toFormatted", "java.time.LocalDateTime", "yyyy-MM-dd HH:mm", false));
      assertNull(date("toFormatted", "java.time.LocalTime", "HH:mm:ss.SSS", false));
      assertNull(date("toFormatted", "java.time.ZonedDateTime", "yyyy-MM-dd HH:mm XXX", false));
      assertNull(date("toFormatted", "java.time.OffsetDateTime", "yyyy-MM-dd HH", true));
      assertTrue(
        date("toFormatted", "java.time.LocalTime", "hh:mm", false).contains("does not read back"),
        "a twelve-hour time with no half of the day"
      );
    }
  }

  @Nested
  @DisplayName("numbers")
  class Numbers {

    @Test
    @DisplayName("every number class but char is formatted, and nothing else is")
    void theNumberClasses() {
      for (final var type : List.of(
        "java.lang.Byte",
        "java.lang.Integer",
        "java.lang.Double",
        "java.math.BigInteger"
      )) {
        assertNull(number("toFormattedNumber", type, "n", "#,##0"), type);
      }
      assertNull(TextFormats.numberOf("java.lang.Character"));
      assertNull(TextFormats.numberOf("java.lang.Boolean"));
      assertNull(TextFormats.numberOf("java.lang.String"));
      assertEquals(
        PairingMessages.formatNotANumber("toFormattedNumber", "p", "AtomicInteger"),
        number("toFormattedNumber", "java.util.concurrent.atomic.AtomicInteger", "AtomicInteger", "0")
      );
    }

    @Test
    @DisplayName("a number pattern that writes fewer digits than a value has is refused both ways")
    void aPatternDroppingDigitsIsRefused() {
      assertEquals(
        PairingMessages.formatReadsBackAs("toFormattedNumber", "p", "0.#E0", "127", "1.3E2", "1.3E+2"),
        number("toFormattedNumber", "java.lang.Integer", "Integer", "0.#E0")
      );
      assertTrue(
        number("toFormattedNumber", "java.math.BigDecimal", "BigDecimal", "##0.##E0").contains("reads back as"),
        "a decimal in engineering notation"
      );
      assertNull(number("toFormattedNumberOneWay", "java.lang.Integer", "Integer", "0.#E0"));
      assertNull(number("toFormattedNumber", "java.math.BigDecimal", "BigDecimal", "#,##0.00"));
      assertNull(number("toFormattedNumber", "java.lang.Double", "Double", "#.##%"));
    }

    @Test
    @DisplayName("an invalid number pattern is refused for the reason the JDK gives")
    void anInvalidPattern() {
      assertEquals(
        PairingMessages.formatPatternInvalid(
          "toFormattedNumberOneWay",
          "p",
          "#.##.0",
          "number",
          "Multiple decimal separators in pattern \"#.##.0\""
        ),
        number("toFormattedNumberOneWay", "java.math.BigDecimal", "BigDecimal", "#.##.0")
      );
    }

    @Test
    @DisplayName("each number class reads back exactly but a float and a double, which take the nearest value")
    void theConversions() {
      assertEquals("intValueExact", TextFormats.exactConversion(Scalar.INT));
      assertEquals("toBigIntegerExact", TextFormats.exactConversion(Scalar.BIG_INTEGER));
      assertEquals("byteValueExact", TextFormats.exactConversion(Scalar.BYTE));
      assertEquals("shortValueExact", TextFormats.exactConversion(Scalar.SHORT));
      assertEquals("longValueExact", TextFormats.exactConversion(Scalar.LONG));
      assertNull(TextFormats.exactConversion(Scalar.BIG_DECIMAL));
      assertNull(TextFormats.exactConversion(Scalar.DOUBLE));
      assertEquals("doubleValue", TextFormats.inexactConversion(Scalar.DOUBLE));
      assertEquals("floatValue", TextFormats.inexactConversion(Scalar.FLOAT));
      assertNull(TextFormats.inexactConversion(Scalar.INT));
    }

    @Test
    @DisplayName("the number format parses into a BigDecimal, with the locale's symbols")
    void theNumberFormat() {
      final var format = TextFormats.numberFormat("#,##0.00", Locale.GERMANY);
      assertEquals("1.234,50", format.format(new BigDecimal("1234.5")));
      final var position = new ParsePosition(0);
      assertEquals(new BigDecimal("1234.50"), format.parse("1.234,50", position));
      assertEquals(8, position.getIndex());
      assertThrows(IllegalArgumentException.class, () -> TextFormats.numberFormat("#.##.0", Locale.ROOT));
    }
  }

  @Test
  @DisplayName("a formatted value's phrase takes the article its type reads with")
  void thePhrase() {
    assertEquals("an Instant in the format \"yyyy\"", PairingMessages.formattedPhrase("Instant", "yyyy"));
    assertEquals("a LocalDate in the format \"dd\"", PairingMessages.formattedPhrase("LocalDate", "dd"));
    assertEquals(
      "'p' got \"x\", not an int in the format \"0\"",
      PairingMessages.notAValue("p", "x", PairingMessages.formattedPhrase("int", "0"))
    );
  }
}
