package io.github.eschizoid.telescope.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.Telescope;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three binders telescope offers for an untyped payload, the streaming {@code JsonBinder}, the
 * runtime {@code Telescope.fromMap} and the binder generated for {@code @FromMap}, read one
 * document into the same value or refuse it in the same words. Each case is a JSON document; the
 * two map binders read the map databind reads that document as, which is the map a service holding
 * the payload as JSON would hand them.
 *
 * <p>A refusal is compared without the line and column the JSON binder adds, which a map has no
 * equivalent of.
 */
class StrictValueCrossPathTest {

  private static final JsonMapper DATABIND = JsonMapper.builder().build();

  private static final Pattern LOCATION = Pattern.compile(", at line \\d+, column \\d+$");

  /** A valid value for every property of {@link StrictScalars}, as JSON literals. */
  private static final Map<String, String> SCALARS = scalars();

  private static Map<String, String> scalars() {
    final var base = new LinkedHashMap<String, String>();
    base.put("b", "1");
    base.put("s", "2");
    base.put("i", "3");
    base.put("l", "4");
    base.put("f", "1.5");
    base.put("d", "2.5");
    base.put("z", "true");
    base.put("c", "\"q\"");
    base.put("boxed", "5");
    base.put("big", "6");
    base.put("decimal", "7.25");
    base.put("locale", "\"en-GB\"");
    base.put("text", "\"t\"");
    base.put("status", "\"PAID\"");
    return base;
  }

  /**
   * One document, and the refusal every binder owes it, or null where every binder reads it and the
   * three values must be equal.
   */
  private record Case(String rule, Class<?> target, String json, String refusal) {}

  /** A {@link StrictScalars} document with {@code key} set to the JSON literal {@code value}. */
  private static Case scalar(final String rule, final String key, final String value, final String refusal) {
    final var document = new LinkedHashMap<>(SCALARS);
    document.put(key, value);
    final var json = new StringBuilder("{");
    for (final var entry : document.entrySet()) {
      if (json.length() > 1) json.append(',');
      json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
    }
    return new Case(rule, StrictScalars.class, json.append('}').toString(), refusal);
  }

  private static Case order(final String rule, final String json, final String refusal) {
    return new Case(rule, StrictOrder.class, json, refusal);
  }

  private static final List<Case> CASES = List.of(
    scalar("every scalar in its own kind", "b", "1", null),
    scalar("an integer past a byte", "b", "128", "'b' got 128, not a byte"),
    scalar("an integer below a byte", "b", "-129", "'b' got -129, not a byte"),
    scalar("an integer past a short", "s", "40000", "'s' got 40000, not a short"),
    scalar("an integer past an int", "i", "3000000000", "'i' got 3000000000, not an int"),
    scalar("an integer past a long", "l", "18446744073709551616", "'l' got 18446744073709551616, not a long"),
    scalar("an integer past an Integer", "boxed", "3000000000", "'boxed' got 3000000000, not an Integer"),
    scalar("a fraction into an int", "i", "1.5", "'i' got 1.5, not an int"),
    scalar("a fraction into a long", "l", "2.5", "'l' got 2.5, not a long"),
    scalar("a fraction into an Integer", "boxed", "1.5", "'boxed' got 1.5, not an Integer"),
    scalar("a fraction into a BigInteger", "big", "1.5", "'big' got 1.5, not a BigInteger"),
    scalar("a number past a float", "f", "3.5E38", "'f' got 3.5E38, not a float"),
    scalar("text into a boolean", "z", "\"yes\"", "'z' got \"yes\", not a boolean"),
    scalar("a number into a boolean", "z", "1", "'z' got 1, not a boolean"),
    scalar("text of two characters into a char", "c", "\"ab\"", "'c' got \"ab\", not a char"),
    scalar("empty text into a char", "c", "\"\"", "'c' got \"\", not a char"),
    scalar("a locale tag that is not well formed", "locale", "\"::bad::\"", "'locale' got \"::bad::\", not a Locale"),
    scalar("a locale written with an underscore", "locale", "\"en_US\"", "'locale' got \"en_US\", not a Locale"),
    scalar("a boolean where a locale's text belongs", "locale", "true", "'locale' got true, not a Locale"),
    scalar("a number where a locale's text belongs", "locale", "12", "'locale' got 12, not a Locale"),
    scalar("a name no constant has", "status", "\"NOPE\"", "'status' got \"NOPE\", not a constant of Status"),
    scalar("a number into a String", "text", "12", "'text' got 12, not a String"),
    order(
      "containers of every kind",
      "{\"lines\":[{\"sku\":\"A\",\"quantity\":1}],\"codes\":[1,2,2],\"counts\":{\"a\":1},\"numbers\":[1,null]}",
      null
    ),
    order(
      "an element that is refused",
      "{\"lines\":[{\"sku\":\"A\",\"quantity\":1},{\"sku\":\"B\",\"quantity\":1.5}]}",
      "'lines[1].quantity' got 1.5, not an int"
    ),
    order("an element that is no object", "{\"lines\":[5]}", "'lines[0]' got 5, not a StrictLine"),
    order("an element of a set that is refused", "{\"codes\":[1,true]}", "'codes[1]' got true, not an Integer"),
    order(
      "a value of a map that is refused",
      "{\"counts\":{\"a\":1,\"b\":1.5}}",
      "'counts[b]' got 1.5, not an Integer"
    ),
    order("text where a list belongs", "{\"numbers\":\"nope\"}", "'numbers' got \"nope\", not a List"),
    order("a number where a set belongs", "{\"codes\":5}", "'codes' got 5, not a Set")
  );

  private static final Map<Class<?>, Function<Map<String, Object>, Object>> GENERATED = Map.of(
    StrictScalars.class,
    StrictScalarsFromMap::fromMap,
    StrictOrder.class,
    StrictOrderFromMap::fromMap
  );

  @Test
  @DisplayName("the JSON binder and both map binders read each document alike, or refuse it in the same words")
  void everyBinderReadsAlike() {
    final var failures = new ArrayList<String>();
    for (final var c : CASES) {
      @SuppressWarnings("unchecked")
      final Map<String, Object> map = DATABIND.readValue(c.json(), Map.class);
      final var json = outcome(() -> TelescopeJson.fromJson(c.target()).read(c.json()));
      final var runtime = outcome(() -> Telescope.fromMap(c.target()).forward(map));
      final var generated = outcome(() -> GENERATED.get(c.target()).apply(map));
      final var owed = c.refusal() == null ? json : "refused " + c.refusal();
      if (c.refusal() == null && json.startsWith("refused")) failures.add(c.rule() + ": the JSON binder " + json);
      if (!json.equals(owed)) failures.add(c.rule() + ": JSON " + json + ", owed " + owed);
      if (!runtime.equals(owed)) failures.add(c.rule() + ": runtime fromMap " + runtime + ", owed " + owed);
      if (!generated.equals(owed)) failures.add(c.rule() + ": generated @FromMap " + generated + ", owed " + owed);
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("a whole double above 2^53 reads into a long as its shortest decimal, the number JSON writes for it")
  void aLargeDoubleIsItsShortestDecimal() {
    final var owed = 1_234_567_890_123_456_800L;
    final Map<String, Object> map = Map.of("l", 1.2345678901234568E18);
    assertEquals(owed, Telescope.fromMap(StrictScalars.class).forward(map).l());
    assertEquals(owed, StrictScalarsFromMap.fromMap(map).l());
    assertEquals(owed, TelescopeJson.fromJson(StrictScalars.class).read("{\"l\":1234567890123456800}").l());
  }

  @Test
  @DisplayName(
    "a number written 1e23 is ten to the 23rd on every reader, as a BigDecimal and, from a map, as a BigInteger"
  )
  void oneValueForADouble() {
    final var tenTo23 = new BigDecimal(BigInteger.TEN.pow(23));
    final var json = "{\"big\":1e23,\"decimal\":1e23}";
    @SuppressWarnings("unchecked")
    final Map<String, Object> map = DATABIND.readValue(json, Map.class);
    final var runtime = Telescope.fromMap(StrictScalars.class).forward(map);
    final var generated = StrictScalarsFromMap.fromMap(map);
    for (final var read : List.of(runtime, generated)) {
      assertEquals(0, tenTo23.compareTo(read.decimal()), () -> "BigDecimal " + read.decimal());
      assertEquals(0, tenTo23.compareTo(new BigDecimal(read.big())), () -> "BigInteger " + read.big());
    }
    final var decimal = TelescopeJson.fromJson(StrictScalars.class).read("{\"decimal\":1e23}").decimal();
    assertEquals(0, tenTo23.compareTo(decimal), () -> "JSON BigDecimal " + decimal);
    // JSON reads a BigInteger from an integer token only, so the fraction token 1e23 is refused by
    // its kind; a map holds the parsed double, which has no kind but its value.
    assertEquals(
      "'big' got 1e23, not a BigInteger, at line 1, column 8",
      assertThrows(IllegalArgumentException.class, () ->
        TelescopeJson.fromJson(StrictScalars.class).read(json)
      ).getMessage()
    );
  }

  /** A read that yields a value or throws. */
  private interface Read {
    Object run();
  }

  /**
   * What a read produced, in a form three binders can be compared by: the value, or the message of
   * the refusal without the location only JSON has. Anything but an {@code
   * IllegalArgumentException} escapes, failing the test.
   */
  private static String outcome(final Read read) {
    try {
      return "value " + read.run();
    } catch (final IllegalArgumentException refused) {
      return "refused " + LOCATION.matcher(refused.getMessage()).replaceFirst("");
    }
  }
}
