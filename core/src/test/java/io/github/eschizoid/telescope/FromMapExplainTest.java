package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static io.github.eschizoid.telescope.mapping.MapExtractStep.required;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.introspection.OpticNode.Extracted;
import io.github.eschizoid.telescope.introspection.OpticNode.Reason;
import io.github.eschizoid.telescope.introspection.OpticNode.Skipped;
import io.github.eschizoid.telescope.introspection.OpticNode.WhenAbsent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the report says a {@code fromMap} binder does to each slot. */
class FromMapExplainTest {

  record Row(String name, int count) {}

  @Test
  @DisplayName("each row is reported with its key, its field, the field's type and what an absent key does")
  void aRowReportsItsKeyFieldTypeAndAbsence() {
    final var report = Telescope.fromMap(
      Row.class,
      extract("n", Row::name, Object::toString),
      required("c", Row::count, v -> Integer.parseInt(v.toString()))
    ).explain();

    assertEquals(
      List.of(
        new Extracted("n", "name", "String", WhenAbsent.DEFAULTS),
        new Extracted("c", "count", "int", WhenAbsent.REFUSES)
      ),
      report.extractions()
    );
    assertEquals(List.of(), report.transformations(), "a map row is not reported as an unconditional conversion");
  }

  @Test
  @DisplayName("an extract row and a required row on the same slot explain differently, as they behave differently")
  void requiredAndExtractExplainDifferently() {
    final var optional = Telescope.fromMap(Row.class, extract("n", Row::name, Object::toString));
    final var must = Telescope.fromMap(Row.class, required("n", Row::name, Object::toString));

    assertEquals(new Row(null, 0), optional.forward(Map.of()));
    assertThrows(IllegalArgumentException.class, () -> must.forward(Map.of()));
    assertNotEquals(optional.explain().nodes(), must.explain().nodes());
    assertTrue(
      optional.explain().toString().contains("→ name String (default when absent)"),
      optional.explain()::toString
    );
    assertTrue(must.explain().toString().contains("→ name String (required)"), must.explain()::toString);
  }

  @Test
  @DisplayName("a slot no row names is reported as read from the key with its own name")
  void aSlotNoRowNamesIsReportedAsReadByItsOwnName() {
    final var report = Telescope.fromMap(Row.class, extract("n", Row::name, Object::toString)).explain();

    assertEquals(
      List.of(
        new Extracted("n", "name", "String", WhenAbsent.DEFAULTS),
        new Extracted("count", "count", "int", WhenAbsent.DEFAULTS)
      ),
      report.extractions()
    );
    assertEquals(List.of(), report.skipped(), "every slot is read from some key");
  }

  /** A bean with a property its writer cannot set: a getter with no setter behind it. */
  public static class Derived {

    private String name;

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public int getLength() {
      return name == null ? 0 : name.length();
    }
  }

  @Test
  @DisplayName("a bean property no row names and the writer cannot set is reported as skipped")
  void anUnwritablePropertyIsReportedAsSkipped() {
    final var report = Telescope.fromMap(Derived.class).explain();

    assertEquals(List.of(new Extracted("name", "name", "String", WhenAbsent.DEFAULTS)), report.extractions());
    assertEquals(List.of(new Skipped("length", Reason.MISSING_SOURCE)), report.skipped());
    assertEquals("Ada", Telescope.fromMap(Derived.class).forward(Map.of("name", "Ada", "length", 9)).getName());
  }

  @Test
  @DisplayName("a bean property the writer cannot set is never read, so a value that does not convert refuses nothing")
  void anUnwritablePropertyIsNotRead() {
    final var derived = Telescope.fromMap(Derived.class).forward(Map.of("name", "Ada", "length", "not an int"));

    assertEquals("Ada", derived.getName());
    assertEquals(3, derived.getLength());
  }

  @Test
  @DisplayName("a trace shows the value read under each key, and marks a slot an absent key left at its default")
  void aTraceShowsTheKeyValueAndTheDefault() {
    final var mapper = Telescope.fromMap(
      Row.class,
      extract("n", Row::name, Object::toString),
      extract("c", Row::count, v -> Integer.parseInt(v.toString()))
    );

    final var trace = mapper.trace(Map.of("n", "Ada")).toString();

    assertTrue(trace.contains("→ name \"Ada\""), trace);
    assertTrue(trace.contains("(absent) → count 0 (default)"), trace);
  }
}
