package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The conversions every map binder and the JSON binder share: a value kept whole, or refused with
 * the property, the value and what it is not.
 */
class MapValueConversionsTest {

  private enum Tone {
    LOW,
    HIGH,
  }

  /** An enum sharing a constant's name with {@link Tone}. */
  private enum Pitch {
    HIGH,
  }

  /**
   * Text whose characters are known only through its {@code toString}, as a lazily rendered
   * sequence's are, so a conversion reading it any other way reads something else.
   */
  private static final class Rendered implements CharSequence {

    private final String rendered;

    Rendered(final String rendered) {
      this.rendered = rendered;
    }

    @Override
    public int length() {
      return 0;
    }

    @Override
    public char charAt(final int index) {
      throw new IndexOutOfBoundsException(index);
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
      return "";
    }

    @Override
    public String toString() {
      return rendered;
    }
  }

  private static String refusal(final Runnable conversion) {
    return assertThrows(ValueRefused.class, conversion::run).getMessage();
  }

  @Nested
  @DisplayName("an integral type")
  class Integral {

    @Test
    @DisplayName("takes a whole number of any class in range, and the text its own parse reads")
    void takesWholeNumbers() {
      assertEquals(7, MapValueConversions.toInt(7L, true, "n"));
      assertEquals(7, MapValueConversions.toInt((short) 7, true, "n"));
      assertEquals(7, MapValueConversions.toInt((byte) 7, true, "n"));
      assertEquals(2, MapValueConversions.toInt(2.0d, true, "n"));
      assertEquals(2, MapValueConversions.toInt(2.0f, true, "n"));
      assertEquals(9, MapValueConversions.toInt(new BigDecimal("9.000"), true, "n"));
      assertEquals(9, MapValueConversions.toInt(BigInteger.valueOf(9), true, "n"));
      assertEquals(5, MapValueConversions.toInt(new AtomicInteger(5), true, "n"));
      assertEquals(-12, MapValueConversions.toInt("-12", true, "n"));
      assertEquals(Long.MAX_VALUE, MapValueConversions.toLong(BigInteger.valueOf(Long.MAX_VALUE), true, "n"));
      assertEquals((short) -32768, MapValueConversions.toShort(-32768, true, "n"));
      assertEquals((byte) 127, MapValueConversions.toByte("127", true, "n"));
    }

    @Test
    @DisplayName("refuses a value past its range, a fraction, a non-finite number and text no parse reads")
    void refusesLoss() {
      assertEquals("'b' got 128, not a byte", refusal(() -> MapValueConversions.toByte(128, true, "b")));
      assertEquals("'b' got \"-129\", not a Byte", refusal(() -> MapValueConversions.toByte("-129", false, "b")));
      assertEquals("'s' got 40000, not a short", refusal(() -> MapValueConversions.toShort(40_000, true, "s")));
      assertEquals(
        "'i' got 3000000000, not an int",
        refusal(() -> MapValueConversions.toInt(3_000_000_000L, true, "i"))
      );
      assertEquals("'i' got 1.5, not an int", refusal(() -> MapValueConversions.toInt(1.5d, true, "i")));
      assertEquals("'i' got NaN, not an Integer", refusal(() -> MapValueConversions.toInt(Float.NaN, false, "i")));
      assertEquals("'i' got \"12.0\", not an int", refusal(() -> MapValueConversions.toInt("12.0", true, "i")));
      assertEquals(
        "'l' got 18446744073709551616, not a long",
        refusal(() -> MapValueConversions.toLong(BigInteger.TWO.pow(64), true, "l"))
      );
      assertEquals(
        "'l' got 2.5, not a long",
        refusal(() -> MapValueConversions.toLong(new BigDecimal("2.5"), true, "l"))
      );
      assertEquals("'l' got true, not a long", refusal(() -> MapValueConversions.toLong(true, true, "l")));
      final var adder = new DoubleAdder();
      adder.add(Double.POSITIVE_INFINITY);
      assertEquals("'l' got Infinity, not a long", refusal(() -> MapValueConversions.toLong(adder, true, "l")));
    }

    @Test
    @DisplayName("a BigInteger takes a whole number of any class and its text, exactly")
    void bigInteger() {
      assertEquals(BigInteger.TWO, MapValueConversions.toValue(2.0d, BigInteger.class, "n"));
      assertEquals(BigInteger.valueOf(7), MapValueConversions.toValue(7, BigInteger.class, "n"));
      assertEquals(BigInteger.valueOf(7), MapValueConversions.toValue(7L, BigInteger.class, "n"));
      assertEquals(BigInteger.valueOf(7), MapValueConversions.toValue((short) 7, BigInteger.class, "n"));
      assertEquals(BigInteger.valueOf(7), MapValueConversions.toValue((byte) 7, BigInteger.class, "n"));
      assertEquals(new BigInteger("99"), MapValueConversions.toValue("99", BigInteger.class, "n"));
      assertEquals(
        "'n' got 1.5, not a BigInteger",
        refusal(() -> MapValueConversions.toValue(1.5d, BigInteger.class, "n"))
      );
      assertEquals(
        "'n' got NaN, not a BigInteger",
        refusal(() -> MapValueConversions.toValue(Double.NaN, BigInteger.class, "n"))
      );
      assertEquals(
        "'n' got 'x', not a BigInteger",
        refusal(() -> MapValueConversions.toValue('x', BigInteger.class, "n"))
      );
    }
  }

  @Nested
  @DisplayName("a floating type")
  class Floating {

    @Test
    @DisplayName("takes any number at its nearest value, and an infinity it is given")
    void takesNumbers() {
      assertEquals(1.5d, MapValueConversions.toDouble(1.5f, true, "d"));
      assertEquals(3.0d, MapValueConversions.toDouble(3, true, "d"));
      assertEquals(Double.POSITIVE_INFINITY, MapValueConversions.toDouble(Double.POSITIVE_INFINITY, true, "d"));
      assertEquals(Double.NEGATIVE_INFINITY, MapValueConversions.toDouble("-Infinity", true, "d"));
      assertEquals(0.5f, MapValueConversions.toFloat(0.5d, true, "f"));
      assertEquals(0.5f, MapValueConversions.toFloat(0.5f, true, "f"));
      assertEquals(Float.POSITIVE_INFINITY, MapValueConversions.toFloat("+Infinity", true, "f"));
      assertEquals(2.5f, MapValueConversions.toFloat("2.5", true, "f"));
    }

    @Test
    @DisplayName("refuses a number that overflows into an infinity, and what is no number")
    void refusesOverflow() {
      assertEquals("'f' got 1.0E300, not a float", refusal(() -> MapValueConversions.toFloat(1e300d, true, "f")));
      assertEquals("'d' got \"1e400\", not a double", refusal(() -> MapValueConversions.toDouble("1e400", true, "d")));
      assertEquals(
        "'d' got 1E+400, not a Double",
        refusal(() -> MapValueConversions.toDouble(new BigDecimal("1e400"), false, "d"))
      );
      assertEquals("'d' got \"x\", not a double", refusal(() -> MapValueConversions.toDouble("x", true, "d")));
      assertEquals("'f' got a List, not a float", refusal(() -> MapValueConversions.toFloat(List.of(), true, "f")));
      assertEquals("'d' got false, not a double", refusal(() -> MapValueConversions.toDouble(false, true, "d")));
    }
  }

  @Test
  @DisplayName("a boolean is a Boolean or true or false in any case, and a char one character")
  void booleanAndChar() {
    assertEquals(true, MapValueConversions.toBoolean("TRUE", true, "z"));
    assertEquals(false, MapValueConversions.toBoolean("False", true, "z"));
    assertEquals(true, MapValueConversions.toBoolean(Boolean.TRUE, true, "z"));
    assertEquals("'z' got \"yes\", not a boolean", refusal(() -> MapValueConversions.toBoolean("yes", true, "z")));
    assertEquals("'z' got 1, not a Boolean", refusal(() -> MapValueConversions.toBoolean(1, false, "z")));
    assertEquals('q', MapValueConversions.toChar("q", true, "c"));
    assertEquals('q', MapValueConversions.toChar('q', true, "c"));
    assertEquals("'c' got \"\", not a char", refusal(() -> MapValueConversions.toChar("", true, "c")));
    assertEquals("'c' got \"ab\", not a Character", refusal(() -> MapValueConversions.toChar("ab", false, "c")));
    assertEquals("'c' got 65, not a char", refusal(() -> MapValueConversions.toChar(65, true, "c")));
  }

  @Test
  @DisplayName("text is taken only as text, a constant by its name, and a JDK value type from its text")
  void textConstantsAndValues() {
    assertEquals("t", MapValueConversions.toText("t", "s"));
    assertEquals("t", MapValueConversions.toText(new StringBuilder("t"), "s"));
    assertEquals(Tone.HIGH, MapValueConversions.toEnum(new StringBuilder("HIGH"), Tone.class, "t"));
    assertEquals(Locale.UK, MapValueConversions.toValue(new StringBuilder("en-GB"), Locale.class, "l"));
    assertEquals(12, MapValueConversions.toInt(new StringBuilder("12"), true, "i"));
    assertEquals(1.5d, MapValueConversions.toDouble(new StringBuilder("1.5"), true, "d"));
    assertEquals(true, MapValueConversions.toBoolean(new StringBuilder("TRUE"), true, "z"));
    assertEquals('q', MapValueConversions.toChar(new StringBuilder("q"), true, "c"));
    assertEquals(
      "'t' got HIGH, not a constant of Tone",
      refusal(() -> MapValueConversions.toEnum(Pitch.HIGH, Tone.class, "t"))
    );
    assertEquals("'t' got 1, not a constant of Tone", refusal(() -> MapValueConversions.toEnum(1, Tone.class, "t")));
    assertEquals(
      "'s' got \"ab\", not a char",
      refusal(() -> MapValueConversions.toChar(new StringBuilder("ab"), true, "s"))
    );
    assertEquals("q", MapValueConversions.toText(new Rendered("q"), "s"));
    assertEquals('q', MapValueConversions.toChar(new Rendered("q"), true, "c"));
    assertNull(MapValueConversions.toText(null, "s"));
    assertEquals("'s' got 12, not a String", refusal(() -> MapValueConversions.toText(12, "s")));
    assertEquals("cs", MapValueConversions.toCharSequence(new StringBuilder("cs"), "s").toString());
    assertNull(MapValueConversions.toCharSequence(null, "s"));
    assertEquals("'s' got 12, not a CharSequence", refusal(() -> MapValueConversions.toCharSequence(12, "s")));
    assertEquals(Tone.HIGH, MapValueConversions.toEnum("HIGH", Tone.class, "t"));
    assertEquals(Tone.LOW, MapValueConversions.toEnum(Tone.LOW, Tone.class, "t"));
    assertNull(MapValueConversions.toEnum(null, Tone.class, "t"));
    assertEquals(
      "'t' got \"high\", not a constant of Tone",
      refusal(() -> MapValueConversions.toEnum("high", Tone.class, "t"))
    );
    assertEquals(Instant.EPOCH, MapValueConversions.toValue("1970-01-01T00:00:00Z", Instant.class, "w"));
    assertEquals(Instant.EPOCH, MapValueConversions.toValue(Instant.EPOCH, Instant.class, "w"));
    assertNull(MapValueConversions.toValue(null, UUID.class, "w"));
    final var unparsable = assertThrows(ValueRefused.class, () ->
      MapValueConversions.toValue("then", Instant.class, "w")
    );
    assertEquals("'w' got \"then\", not an Instant", unparsable.getMessage());
    assertInstanceOf(DateTimeParseException.class, unparsable.getCause());
    assertEquals(new BigDecimal("0.1"), MapValueConversions.toValue(0.1d, BigDecimal.class, "m"));
    assertEquals(new BigDecimal("7"), MapValueConversions.toValue(BigInteger.valueOf(7), BigDecimal.class, "m"));
    assertEquals(new BigDecimal("7"), MapValueConversions.toValue(7L, BigDecimal.class, "m"));
    assertEquals(new BigDecimal("7"), MapValueConversions.toValue((short) 7, BigDecimal.class, "m"));
    assertEquals(new BigDecimal("7"), MapValueConversions.toValue((byte) 7, BigDecimal.class, "m"));
    assertEquals(new BigDecimal("1.10"), MapValueConversions.toValue("1.10", BigDecimal.class, "m"));
    assertEquals(
      "'m' got NaN, not a BigDecimal",
      refusal(() -> MapValueConversions.toValue(Double.NaN, BigDecimal.class, "m"))
    );
    assertEquals(
      "'m' got true, not a BigDecimal",
      refusal(() -> MapValueConversions.toValue(true, BigDecimal.class, "m"))
    );
    assertThrows(IllegalStateException.class, () -> MapValueConversions.toValue("x", Thread.class, "m"));
  }

  @Test
  @DisplayName("a locale is read from a well-formed language tag only")
  void locale() {
    assertEquals(Locale.UK, MapValueConversions.toValue("en-GB", Locale.class, "l"));
    assertEquals(
      "'l' got \"::bad::\", not a Locale",
      refusal(() -> MapValueConversions.toValue("::bad::", Locale.class, "l"))
    );
    assertEquals(
      "'l' got \"en_US\", not a Locale",
      refusal(() -> MapValueConversions.toValue("en_US", Locale.class, "l"))
    );
    assertEquals("'l' got true, not a Locale", refusal(() -> MapValueConversions.toValue(true, Locale.class, "l")));
    assertEquals(
      "'l' got NaN, not a Locale",
      refusal(() -> MapValueConversions.toValue(Double.NaN, Locale.class, "l"))
    );
  }

  @Test
  @DisplayName("a double has one value whether it is read as a BigInteger or a BigDecimal")
  void oneValuePerDouble() {
    final var tenTo23 = BigInteger.TEN.pow(23);
    assertEquals(tenTo23, MapValueConversions.toValue(1e23d, BigInteger.class, "n"));
    assertEquals(0, new BigDecimal(tenTo23).compareTo(MapValueConversions.toValue(1e23d, BigDecimal.class, "n")));
    assertEquals(BigInteger.TEN.pow(10), MapValueConversions.toValue(1e10f, BigInteger.class, "n"));
  }

  @Nested
  @DisplayName("a container")
  class Containers {

    private final Function<Object, Integer> integer = v -> v == null ? null : MapValueConversions.toInt(v, false, null);

    @Test
    @DisplayName("takes a container of its shape, of any kind, and converts every element")
    void converts() {
      assertEquals(List.of(1), MapValueConversions.toList(Set.of(1), "a", integer));
      assertEquals(Arrays.asList(1, null), MapValueConversions.toList(Arrays.asList(1.0d, null), "a", integer));
      assertEquals(Set.of(1), MapValueConversions.toSet(List.of(1, 1L), "a", integer));
      assertEquals(Map.of(1, 2), MapValueConversions.toMap(Map.of("1", 2.0d), "a", integer, integer));
      assertEquals(List.of(), MapValueConversions.toList(null, "a", integer));
      assertEquals(Set.of(), MapValueConversions.toSet(null, "a", integer));
      assertEquals(Map.of(), MapValueConversions.toMap(null, "a", integer, integer));
    }

    @Test
    @DisplayName("refuses a value of another shape, and an element by its position")
    void refuses() {
      assertEquals("'a' got \"x\", not a List", refusal(() -> MapValueConversions.toList("x", "a", integer)));
      assertEquals("'a' got a Map, not a Set", refusal(() -> MapValueConversions.toSet(Map.of(), "a", integer)));
      assertEquals(
        "'a' got an array, not a Map",
        refusal(() -> MapValueConversions.toMap(new int[0], "a", integer, integer))
      );
      assertEquals(
        "'a[1]' got 1.5, not an Integer",
        refusal(() -> MapValueConversions.toList(List.of(1, 1.5d), "a", integer))
      );
      assertEquals(
        "'a[k]' got \"x\", not an Integer",
        refusal(() -> MapValueConversions.toMap(Map.of("k", "x"), "a", v -> v, integer))
      );
      assertEquals("the value got \"x\", not an int", ValueRefused.value("x", "an int", null).getMessage());
    }

    @Test
    @DisplayName("a nested type is built from a map, and its refusals are placed inside the property")
    void nested() {
      final Function<Map<String, Object>, Integer> binder = map -> MapValueConversions.toInt(map.get("n"), true, "n");
      assertEquals(3, MapValueConversions.nested(Map.of("n", 3), "inner", "Inner", binder));
      assertNull(MapValueConversions.nested(null, "inner", "Inner", binder));
      assertEquals(
        "'inner' got 4, not an Inner",
        refusal(() -> MapValueConversions.nested(4, "inner", "Inner", binder))
      );
      assertEquals(
        "'inner.n' got 1.5, not an int",
        refusal(() -> MapValueConversions.nested(Map.of("n", 1.5d), "inner", "Inner", binder))
      );
    }
  }

  @Test
  @DisplayName("a map missing required keys is refused naming each key and where the map is")
  void missing() {
    final var refused = ValueRefused.missing("Telescope.fromMap", List.of("\"id\" (component 'id')"), "Row");
    assertEquals(
      "Telescope.fromMap: the map carries no value for required key \"id\" (component 'id') of Row",
      refused.getMessage()
    );
    refused.at(2).under("rows");
    assertEquals("rows[2]", refused.path());
    assertEquals(
      "Telescope.fromMap: the map at 'rows[2]' carries no value for required keys \"a\" (component 'a'), \"b\" (component 'b') of Row",
      ValueRefused.missing("Telescope.fromMap", List.of("\"a\" (component 'a')", "\"b\" (component 'b')"), "Row")
        .at(2)
        .under("rows")
        .getMessage()
    );
  }

  @Test
  @DisplayName("a value is shown as the map holds it")
  void shown() {
    assertEquals("null", PairingMessages.mapValueShown(null));
    assertEquals("HIGH", PairingMessages.mapValueShown(Tone.HIGH));
    assertEquals("a Set", PairingMessages.mapValueShown(Set.of()));
    assertEquals("an Instant", PairingMessages.mapValueShown(Instant.EPOCH));
  }
}
