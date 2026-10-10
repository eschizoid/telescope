package io.github.eschizoid.telescope.frommapparity;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static io.github.eschizoid.telescope.mapping.MapExtractStep.required;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.Telescope;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A component no row names is read from the map key with its own name and converted the way the
 * generated binder converts it. Both binders are built from the same fixtures, so for every map
 * below the generated binder and the runtime mapper must hand back the same object, component by
 * component, with and without rows layered on top.
 */
class FromMapBackfillParityTest {

  /** Every component carried as the type it is declared as, nested maps where a binder recurses. */
  private static Map<String, Object> typed() {
    final var m = new LinkedHashMap<String, Object>();
    m.put("i", 7);
    m.put("l", 8L);
    m.put("d", 1.5d);
    m.put("f", 2.5f);
    m.put("s", (short) 3);
    m.put("b", (byte) 4);
    m.put("flag", true);
    m.put("c", 'x');
    m.put("boxedInt", 9);
    m.put("boxedLong", 10L);
    m.put("boxedDouble", 1.25d);
    m.put("boxedFloat", 0.5f);
    m.put("boxedShort", (short) 5);
    m.put("boxedByte", (byte) 6);
    m.put("boxedFlag", false);
    m.put("boxedChar", 'y');
    m.put("text", "t");
    m.put("chars", "cs");
    m.put("opaque", List.of(1, 2));
    m.put("tone", BackfillTone.HIGH);
    m.put("when", Instant.EPOCH);
    m.put("amount", new BigDecimal("1.10"));
    m.put("leaf", Map.of("city", "Rome", "zip", 100));
    m.put("leaves", List.of(Map.of("city", "A", "zip", "1"), Map.of("city", "B")));
    m.put("numbers", List.of(1, "2", 3L));
    m.put("tags", Set.of("a", "b"));
    m.put("counts", Map.of("a", 1, "b", "2"));
    m.put("tonesByKey", Map.of("k", List.of("LOW", BackfillTone.HIGH)));
    m.put("maybe", "m");
    m.put("maybeLeaf", Map.of("city", "Oslo"));
    m.put("maybeLongs", List.of(1, "2"));
    return m;
  }

  /** Every scalar carried as the String an untyped source usually hands over. */
  private static Map<String, Object> strings() {
    final var m = new LinkedHashMap<String, Object>();
    m.put("i", "7");
    m.put("l", "8");
    m.put("d", "1.5");
    m.put("f", "2.5");
    m.put("s", "3");
    m.put("b", "4");
    m.put("flag", "TRUE");
    m.put("c", "xyz");
    m.put("boxedInt", "9");
    m.put("boxedLong", "10");
    m.put("boxedDouble", "1.25");
    m.put("boxedFloat", "0.5");
    m.put("boxedShort", "5");
    m.put("boxedByte", "6");
    m.put("boxedFlag", "yes");
    m.put("boxedChar", "y");
    m.put("text", "t");
    m.put("chars", "cs");
    m.put("opaque", "o");
    m.put("tone", "LOW");
    m.put("when", "2024-01-01T00:00:00Z");
    m.put("amount", "3.30");
    return m;
  }

  /** Numbers of another width, empty strings, and containers of the wrong shape. */
  private static Map<String, Object> mismatched() {
    final var m = new LinkedHashMap<String, Object>();
    m.put("i", 7.9d);
    m.put("l", 8);
    m.put("d", 3);
    m.put("s", 70_000);
    m.put("boxedInt", 9L);
    m.put("boxedDouble", 2);
    m.put("c", "");
    m.put("boxedChar", "");
    m.put("tags", List.of("a"));
    m.put("numbers", "nope");
    m.put("counts", List.of());
    m.put("maybeLongs", Set.of(1));
    return m;
  }

  /** Keys present with a null value, which read the same as keys that are absent. */
  private static Map<String, Object> nulls() {
    final var m = new HashMap<String, Object>();
    for (final var name : List.of("i", "flag", "c", "boxedInt", "text", "tone", "leaf", "leaves", "maybe")) {
      m.put(name, null);
    }
    return m;
  }

  private static List<Map<String, Object>> sources() {
    return List.of(typed(), strings(), mismatched(), nulls(), Map.of(), Map.of("unrelated", 1));
  }

  @Test
  @DisplayName("a record with no rows: every map gives the same record on both paths")
  void aRecordWithNoRowsAgrees() {
    final var runtime = Telescope.fromMap(BackfillRow.class);
    for (final var source : sources()) {
      assertSameComponents(BackfillRowFromMap.fromMap(source), runtime.forward(source), source, Set.of());
    }
  }

  @Test
  @DisplayName("a record with a row naming one component: the row decides it, and the rest agree")
  void aRecordWithARowOverridesOnlyWhatItNames() {
    final var runtime = Telescope.fromMap(BackfillRow.class, extract("label", BackfillRow::text, v -> "row:" + v));
    for (final var source : sources()) {
      final var withLabel = new LinkedHashMap<>(source);
      withLabel.put("label", "L");
      final var produced = runtime.forward(withLabel);
      assertSameComponents(BackfillRowFromMap.fromMap(withLabel), produced, withLabel, Set.of("text"));
      assertEquals("row:L", produced.text(), "the row reads its own key, not the component's name");
    }
  }

  @Test
  @DisplayName("a record whose rows name components by their own keys gives the same record as no rows")
  void rowsThatRestateTheDefaultAgree() {
    final var runtime = Telescope.fromMap(
      BackfillRow.class,
      required("i", BackfillRow::i, v -> v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString())),
      extract("text", BackfillRow::text, v -> (String) v)
    );
    for (final var source : List.of(typed(), strings())) {
      assertSameComponents(BackfillRowFromMap.fromMap(source), runtime.forward(source), source, Set.of());
    }
  }

  @Test
  @DisplayName("a bean with no rows: every map gives the same property values on both paths")
  void aBeanWithNoRowsAgrees() {
    final var runtime = Telescope.fromMap(BackfillBean.class);
    final var typed = Map.<String, Object>of(
      "count",
      "12",
      "flag",
      true,
      "text",
      "t",
      "tone",
      "HIGH",
      "leaf",
      Map.of("city", "Rome", "zip", "7"),
      "numbers",
      List.of(1, "2"),
      "maybe",
      "m"
    );
    for (final var source : List.of(
      typed,
      Map.<String, Object>of(),
      Map.<String, Object>of("count", 3.7d),
      Map.<String, Object>of("flag", "TRUE", "initial", "xyz"),
      Map.<String, Object>of("flag", "no", "initial", ""),
      Map.<String, Object>of("flag", false, "initial", 'q')
    )) {
      assertSameProperties(BackfillBeanFromMap.fromMap(source), runtime.forward(source), source);
    }
  }

  @Test
  @DisplayName("a nested type is built through its generated binder, so its required key is refused on both paths")
  void aNestedBinderRefusesTheSameMapOnBothPaths() {
    final var record = Map.<String, Object>of("leaf", Map.of("zip", 1));
    final var generated = assertThrows(IllegalArgumentException.class, () -> BackfillRowFromMap.fromMap(record));
    final var runtime = assertThrows(IllegalArgumentException.class, () ->
      Telescope.fromMap(BackfillRow.class).forward(record)
    );
    assertEquals(generated.getMessage(), runtime.getMessage());

    final var bean = Map.<String, Object>of("leaf", Map.of("zip", 1));
    final var generatedBean = assertThrows(IllegalArgumentException.class, () -> BackfillBeanFromMap.fromMap(bean));
    final var runtimeBean = assertThrows(IllegalArgumentException.class, () ->
      Telescope.fromMap(BackfillBean.class).forward(bean)
    );
    assertEquals(generatedBean.getMessage(), runtimeBean.getMessage());
  }

  @Test
  @DisplayName("a value neither path can convert fails the same way on both")
  void anUnconvertibleValueFailsTheSameWay() {
    for (final var source : List.<Map<String, Object>>of(
      Map.of("i", "seven"),
      Map.of("tone", "MIDDLE"),
      Map.of("text", 5),
      Map.of("when", "yesterday"),
      Map.of("leaf", "Rome")
    )) {
      final var generated = assertThrows(RuntimeException.class, () -> BackfillRowFromMap.fromMap(source));
      final var runtime = assertThrows(RuntimeException.class, () ->
        Telescope.fromMap(BackfillRow.class).forward(source)
      );
      // Each failure is the platform's own (a parse, an enum lookup, a cast), raised by the same
      // call on both paths, so the message is the same too and pins which call failed.
      assertEquals(generated.getClass(), runtime.getClass(), () -> "for " + source);
      assertEquals(generated.getMessage(), runtime.getMessage(), () -> "for " + source);
    }
  }

  private static void assertSameComponents(
    final BackfillRow generated,
    final BackfillRow runtime,
    final Map<String, Object> source,
    final Set<String> overridden
  ) {
    final var differences = new ArrayList<String>();
    for (final RecordComponent component : BackfillRow.class.getRecordComponents()) {
      if (overridden.contains(component.getName())) continue;
      final var accessor = component.getAccessor();
      final Object want = read(accessor, generated);
      final Object got = read(accessor, runtime);
      if (!Objects.equals(want, got)) {
        differences.add(component.getName() + ": generated " + want + ", runtime " + got);
      }
    }
    assertEquals(List.of(), differences, () -> "for " + source + "\n" + String.join("\n", differences));
  }

  private static void assertSameProperties(
    final BackfillBean generated,
    final BackfillBean runtime,
    final Map<String, Object> source
  ) {
    final List<Function<BackfillBean, Object>> getters = List.of(
      BackfillBean::getCount,
      BackfillBean::getFlag,
      BackfillBean::getText,
      BackfillBean::getTone,
      BackfillBean::getLeaf,
      BackfillBean::getNumbers,
      BackfillBean::getMaybe,
      BackfillBean::getInitial
    );
    final var want = getters
      .stream()
      .map(g -> g.apply(generated))
      .toList();
    final var got = getters
      .stream()
      .map(g -> g.apply(runtime))
      .toList();
    assertEquals(want, got, () -> "for " + source + ": " + Arrays.asList(want.toArray()));
  }

  private static Object read(final Method accessor, final Object target) {
    try {
      return accessor.invoke(target);
    } catch (final ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
