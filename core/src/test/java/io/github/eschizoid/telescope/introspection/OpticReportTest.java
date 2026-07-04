package io.github.eschizoid.telescope.introspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.introspection.OpticNode.Focus;
import io.github.eschizoid.telescope.introspection.OpticNode.Mapped;
import io.github.eschizoid.telescope.introspection.OpticNode.Reason;
import io.github.eschizoid.telescope.introspection.OpticNode.Skipped;
import io.github.eschizoid.telescope.introspection.OpticNode.Transformed;
import io.github.eschizoid.telescope.introspection.OpticNode.Traverse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link OpticReport} — the data-first result of {@code explain()}. Pins the
 * typed slices ({@link OpticReport#mapped()} / {@code transformations()} / {@code skipped()}), the
 * empty-report behaviour, the defensive copy of the node list, and the sectioned {@code toString()}
 * render for both a mapping report and a navigation report.
 */
class OpticReportTest {

  @Nested
  @DisplayName("Typed slices filter the trail by node kind, in order")
  class Slices {

    private final OpticReport report = new OpticReport(
      List.of(
        new Mapped("firstName", "firstName", "givenName"),
        new Transformed("birthDate", "String", "LocalDate"),
        new Skipped("id", Reason.DROPPED),
        new Mapped("address.city", "address.city", "city"),
        new Skipped("region", Reason.MISSING_SOURCE)
      )
    );

    @Test
    @DisplayName("mapped() returns only the Mapped nodes, in trail order")
    void mappedSlice() {
      assertEquals(
        List.of(new Mapped("firstName", "firstName", "givenName"), new Mapped("address.city", "address.city", "city")),
        report.mapped()
      );
    }

    @Test
    @DisplayName("transformations() returns only the Transformed nodes")
    void transformedSlice() {
      assertEquals(List.of(new Transformed("birthDate", "String", "LocalDate")), report.transformations());
    }

    @Test
    @DisplayName("skipped() returns the Skipped nodes with their reasons, in trail order")
    void skippedSlice() {
      assertEquals(
        List.of(new Skipped("id", Reason.DROPPED), new Skipped("region", Reason.MISSING_SOURCE)),
        report.skipped()
      );
    }

    @Test
    @DisplayName("a non-empty report is not empty")
    void notEmpty() {
      assertFalse(report.isEmpty());
    }
  }

  @Nested
  @DisplayName("Empty report — the bare-identity case")
  class Empty {

    private final OpticReport report = new OpticReport(List.of());

    @Test
    @DisplayName("isEmpty is true and every slice is empty")
    void emptyEverywhere() {
      assertTrue(report.isEmpty());
      assertTrue(report.mapped().isEmpty());
      assertTrue(report.transformations().isEmpty());
      assertTrue(report.skipped().isEmpty());
    }

    @Test
    @DisplayName("toString names the no-mapping case rather than rendering blank sections")
    void emptyRender() {
      assertEquals("(no mapping)", report.toString());
    }
  }

  @Nested
  @DisplayName("Defensive copy — the node list cannot be mutated after construction")
  class Immutability {

    @Test
    @DisplayName("the report copies its input list, so external mutation does not leak in")
    void defensiveCopy() {
      final var mutable = new java.util.ArrayList<OpticNode>();
      mutable.add(new Mapped("a", "a", "a"));
      final var report = new OpticReport(mutable);
      mutable.clear();
      assertEquals(1, report.nodes().size(), "report must not reflect post-construction mutation of the source list");
    }

    @Test
    @DisplayName("nodes() is unmodifiable")
    void unmodifiableNodes() {
      final var report = new OpticReport(List.of(new Mapped("a", "a", "a")));
      assertThrows(UnsupportedOperationException.class, () -> report.nodes().add(new Mapped("b", "b", "b")));
    }
  }

  @Nested
  @DisplayName("toString render")
  class Render {

    @Test
    @DisplayName("a mapping report renders the Mapped / Transformations / Skipped sections")
    void mappingRender() {
      final var report = new OpticReport(
        List.of(
          new Mapped("firstName", "firstName", "givenName"),
          new Transformed("birthDate", "String", "LocalDate"),
          new Skipped("id", Reason.DROPPED)
        )
      );
      final var text = report.toString();
      assertTrue(text.contains("Mapped:"), text);
      assertTrue(text.contains("firstName → givenName"), text);
      assertTrue(text.contains("Transformations:"), text);
      assertTrue(text.contains("birthDate(String) → LocalDate"), text);
      assertTrue(text.contains("Skipped:"), text);
      assertTrue(text.contains("id (dropped)"), text);
    }

    @Test
    @DisplayName("a navigation report renders the hop path under a Path section")
    void navigationRender() {
      final var report = new OpticReport(List.of(new Traverse("departments", "List<Department>"), new Focus("name")));
      final var text = report.toString();
      assertTrue(text.contains("Path:"), text);
      assertTrue(text.contains("Traverse: departments (List<Department>)"), text);
      assertTrue(text.contains("Focus:    name"), text);
    }

    @Test
    @DisplayName("each skip reason renders its human label")
    void reasonLabels() {
      final var report = new OpticReport(
        List.of(
          new Skipped("a", Reason.DROPPED),
          new Skipped("b", Reason.MISSING_SOURCE),
          new Skipped("c", Reason.UNMAPPED_SOURCE)
        )
      );
      final var text = report.toString();
      assertTrue(text.contains("a (dropped)"), text);
      assertTrue(text.contains("b (missing source)"), text);
      assertTrue(text.contains("c (unmapped source)"), text);
    }
  }
}
