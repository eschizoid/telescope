package io.github.eschizoid.telescope.frommapparity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.Telescope;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a map can hold that JSON cannot, and the two map binders read alike: text where a number or
 * a boolean belongs, a whole number of another class, and a collection of another kind. Each is
 * kept where it converts without loss, and refused where it would not, by the generated binder and
 * by {@code Telescope.fromMap}, in the same words.
 */
class FromMapLosslessParityTest {

  /** An enum sharing a constant's name with {@link BackfillTone}, which is still not one of its. */
  private enum Level {
    LOW,
  }

  private static final Function<Map<String, Object>, BackfillRow> GENERATED = BackfillRowFromMap::fromMap;
  private static final Function<Map<String, Object>, BackfillRow> RUNTIME = Telescope.fromMap(
    BackfillRow.class
  )::forward;

  private static Map<String, Object> holding(final Object... keysAndValues) {
    final var map = new LinkedHashMap<String, Object>();
    for (var i = 0; i < keysAndValues.length; i += 2) map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    return map;
  }

  @Test
  @DisplayName("a value that converts without loss is kept, the same on both paths")
  void aLosslessValueIsKept() {
    final var source = holding(
      "i",
      2.0d,
      "l",
      new BigDecimal("9.000"),
      "s",
      "12",
      "b",
      (byte) -128,
      "flag",
      "TRUE",
      "boxedFlag",
      "false",
      "c",
      "z",
      "boxedLong",
      BigInteger.valueOf(Long.MAX_VALUE),
      "d",
      Long.MAX_VALUE,
      "amount",
      7,
      "tags",
      List.of("a", "b", "a"),
      "numbers",
      Set.of(4),
      "leaves",
      List.of(Map.of("city", "Rome", "zip", 1.0d))
    );
    for (final var read : List.of(GENERATED, RUNTIME)) {
      final var row = read.apply(source);
      assertEquals(2, row.i());
      assertEquals(9L, row.l());
      assertEquals((short) 12, row.s());
      assertEquals((byte) -128, row.b());
      assertEquals(true, row.flag());
      assertEquals(Boolean.FALSE, row.boxedFlag());
      assertEquals('z', row.c());
      assertEquals(Long.MAX_VALUE, row.boxedLong());
      assertEquals((double) Long.MAX_VALUE, row.d());
      assertEquals(new BigDecimal("7"), row.amount());
      assertEquals(Set.of("a", "b"), row.tags());
      assertEquals(List.of(4), row.numbers());
      assertEquals(List.of(new BackfillLeaf("Rome", 1)), row.leaves());
    }
  }

  @Test
  @DisplayName("any CharSequence is text, the same on both paths, and a constant of another enum is refused")
  void anyCharSequenceIsText() {
    final var source = holding(
      "text",
      new StringBuilder("t"),
      "chars",
      new StringBuilder("cs"),
      "tone",
      new StringBuilder("LOW"),
      "when",
      new StringBuilder("1970-01-01T00:00:00Z"),
      "i",
      new StringBuilder("7"),
      "flag",
      new StringBuilder("true"),
      "c",
      new StringBuilder("q")
    );
    for (final var read : List.of(GENERATED, RUNTIME)) {
      final var row = read.apply(source);
      assertEquals("t", row.text());
      assertEquals("cs", row.chars().toString());
      assertEquals(BackfillTone.LOW, row.tone());
      assertEquals(Instant.EPOCH, row.when());
      assertEquals(7, row.i());
      assertEquals(true, row.flag());
      assertEquals('q', row.c());
      final var refused = assertThrows(IllegalArgumentException.class, () -> read.apply(holding("tone", Level.LOW)));
      assertEquals("'tone' got LOW, not a constant of BackfillTone", refused.getMessage());
    }
  }

  @Test
  @DisplayName("a value that would lose data is refused, in the same words on both paths, naming where it is")
  void aLossyValueIsRefused() {
    final var cases = new LinkedHashMap<Map<String, Object>, String>();
    cases.put(holding("i", 2.5d), "'i' got 2.5, not an int");
    cases.put(holding("i", Double.NaN), "'i' got NaN, not an int");
    cases.put(holding("l", Double.POSITIVE_INFINITY), "'l' got Infinity, not a long");
    cases.put(holding("s", "12.0"), "'s' got \"12.0\", not a short");
    cases.put(holding("b", 200), "'b' got 200, not a byte");
    cases.put(holding("boxedByte", "200"), "'boxedByte' got \"200\", not a Byte");
    cases.put(holding("f", 1e300d), "'f' got 1.0E300, not a float");
    cases.put(holding("d", "1e400"), "'d' got \"1e400\", not a double");
    cases.put(holding("flag", "yes"), "'flag' got \"yes\", not a boolean");
    cases.put(holding("boxedFlag", 0), "'boxedFlag' got 0, not a Boolean");
    cases.put(holding("c", 65), "'c' got 65, not a char");
    cases.put(holding("boxedChar", "ab"), "'boxedChar' got \"ab\", not a Character");
    cases.put(holding("text", List.of()), "'text' got a List, not a String");
    cases.put(holding("amount", "1,5"), "'amount' got \"1,5\", not a BigDecimal");
    cases.put(holding("tags", "a"), "'tags' got \"a\", not a Set");
    cases.put(holding("counts", List.of(1)), "'counts' got a List, not a Map");
    cases.put(holding("numbers", List.of(1, 1.5d)), "'numbers[1]' got 1.5, not an Integer");
    cases.put(
      holding("tonesByKey", Map.of("k", List.of("LOW", "LOUD"))),
      "'tonesByKey[k][1]' got \"LOUD\", not a constant of BackfillTone"
    );
    cases.put(
      holding("leaves", List.of(Map.of("city", "A"), Map.of("city", "B", "zip", 2.5d))),
      "'leaves[1].zip' got 2.5, not an int"
    );
    cases.put(holding("leaf", "Rome"), "'leaf' got \"Rome\", not a BackfillLeaf");
    cases.put(holding("leaf", Map.of("city", "A", "zip", 2.5d)), "'leaf.zip' got 2.5, not an int");
    cases.put(holding("maybeLongs", List.of(1, "x")), "'maybeLongs[1]' got \"x\", not a Long");
    final var failures = new ArrayList<String>();
    for (final var c : cases.entrySet()) {
      for (final var read : List.of(GENERATED, RUNTIME)) {
        final var refused = assertThrows(IllegalArgumentException.class, () -> read.apply(c.getKey()));
        if (!c.getValue().equals(refused.getMessage())) failures.add(c.getKey() + ": " + refused.getMessage());
      }
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("a nested map missing a required key is refused naming where the map is")
  void aNestedMissingKeyNamesItsPlace() {
    final var source = holding("leaves", List.of(Map.of("city", "A"), Map.of("zip", 2)));
    for (final var read : List.of(GENERATED, RUNTIME)) {
      final var refused = assertThrows(IllegalArgumentException.class, () -> read.apply(source));
      assertEquals(
        "BackfillLeafFromMap.fromMap: the map at 'leaves[1]' carries no value for required key \"city\"" +
          " (component 'city') of BackfillLeaf",
        refused.getMessage()
      );
    }
  }
}
