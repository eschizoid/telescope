package io.github.eschizoid.telescope.introspection;

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
}
