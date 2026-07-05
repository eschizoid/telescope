package io.github.eschizoid.telescope.introspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.introspection.OpticNode.Mapped;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@code explain()} / {@code trace()} on a mapping-built {@code Telescope} (the
 * {@code Telescope.map(A, B, …)} declarative-conversion factory), as opposed to the {@code Mapper}
 * family. Its report carries the field {@link OpticNode.Row rows}, and its {@code trace} renders
 * those rows statically rather than fanning out like a navigation path.
 */
class MappingTelescopeIntrospectionTest {

  record Source(String name, String city) {}

  record Target(String name, String city) {}

  @Test
  @DisplayName("map(...).explain() surfaces the same-name field rows")
  void mapExplainSurfacesRows() {
    final var report = Telescope.map(Source.class, Target.class).explain();
    assertTrue(report.mapped().contains(new Mapped("name", "name")), report::toString);
    assertTrue(report.mapped().contains(new Mapped("city", "city")), report::toString);
    assertTrue(report.skipped().isEmpty(), report::toString);
  }

  @Test
  @DisplayName("map(...).trace(input) renders the value column — source value → target value per row")
  void mapTraceRendersValues() {
    final var trace = Telescope.map(Source.class, Target.class).trace(new Source("Ada", "Paris"));
    final var text = trace.toString();
    // A mapping telescope's trace shows the same value column as Mapper.trace, not just node names.
    assertTrue(text.contains("name") && text.contains("\"Ada\""), text);
    assertTrue(text.contains("city") && text.contains("\"Paris\""), text);
    assertTrue(text.contains("→"), text);
  }

  @Test
  @DisplayName("an iso-backed telescope (from/to/using, empty trail) traces the executed output, not the input")
  void isoBackedTraceShowsExecutedOutput() {
    final var iso = Telescope.from(String.class).to(Integer.class).using(Integer::parseInt, Object::toString);
    // Empty trail: trace must render the converted focus (42), not the raw input ("42" quoted).
    assertEquals("42", iso.trace("42").toString());
  }

  @Test
  @DisplayName(
    "a mapping telescope further navigated (map(...).field(...)) traces the final value, not a misleading row breakdown"
  )
  void mixedRowHopTraceIsSafe() {
    final var trace = Telescope.map(Source.class, Target.class).field(Target::name).trace(new Source("Ada", "Paris"));
    final var text = trace.toString();
    // Mixed Row+Hop trail: the value column would misread mapping rows off the navigated leaf, so
    // trace falls back to the safe final value — the navigated field, never (n/a).
    assertTrue(text.contains("\"Ada\""), text);
    assertFalse(text.contains("(n/a)"), text);
  }
}
