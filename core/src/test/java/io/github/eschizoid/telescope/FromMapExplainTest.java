package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the report says a {@code fromMap} binder does to each slot. */
class FromMapExplainTest {

  record Row(String name, int count) {}

  @Test
  @DisplayName("a row's report names the type the value arrives as, where a type belongs")
  void aRowReportsItsTargetType() {
    // The row's fourth component is the target type, and every other producer of one writes a type
    // there. A word describing the conversion reads as the component's type to anyone who prints
    // the report.
    final var report = Telescope.fromMap(
      Row.class,
      extract("n", Row::name, Object::toString),
      extract("c", Row::count, v -> Integer.parseInt(v.toString()))
    ).explain();

    final var byTarget = report
      .transformations()
      .stream()
      .collect(java.util.stream.Collectors.toMap(t -> t.to(), t -> t.toType()));

    assertEquals(Map.of("name", "String", "count", "int"), byTarget);
    // The source side is a type slot too, and the report prints it inside the parentheses where a
    // type belongs. An untyped map hands over an Object.
    assertEquals(
      Map.of("name", "Object", "count", "Object"),
      report.transformations().stream().collect(java.util.stream.Collectors.toMap(t -> t.to(), t -> t.fromType()))
    );
  }

  @Test
  @DisplayName("a slot no row fills is reported as having no source rather than as a conversion")
  void anUnfilledSlotIsReportedAsMissing() {
    final var report = Telescope.fromMap(Row.class, extract("n", Row::name, Object::toString)).explain();

    assertEquals(1, report.transformations().size(), "one row, one transformation");
    assertTrue(
      report
        .skipped()
        .stream()
        .anyMatch(s -> s.field().equals("count")),
      () -> "count has no row: " + report.skipped()
    );
  }
}
