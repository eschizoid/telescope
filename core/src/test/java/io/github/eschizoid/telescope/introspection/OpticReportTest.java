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
import io.github.eschizoid.telescope.introspection.OpticNode.UnusedSource;
import java.util.ArrayList;
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
        new Mapped("firstName", "givenName"),
        new Transformed("birthDate", "birthDate", "String", "LocalDate"),
        new Skipped("id", Reason.DROPPED),
        new Mapped("address.city", "city"),
        new Skipped("region", Reason.MISSING_SOURCE),
        new UnusedSource("legacyId")
      )
    );

    @Test
    @DisplayName("mapped() returns only the Mapped nodes, in trail order")
    void mappedSlice() {
      assertEquals(List.of(new Mapped("firstName", "givenName"), new Mapped("address.city", "city")), report.mapped());
    }

    @Test
    @DisplayName("transformations() returns only the Transformed nodes")
    void transformedSlice() {
      assertEquals(List.of(new Transformed("birthDate", "birthDate", "String", "LocalDate")), report.transformations());
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
    @DisplayName("unusedSources() returns only the UnusedSource nodes")
    void unusedSourcesSlice() {
      assertEquals(List.of(new UnusedSource("legacyId")), report.unusedSources());
      assertTrue(
        report
          .skipped()
          .stream()
          .noneMatch(s -> s.field().equals("legacyId")),
        report::toString
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
    @DisplayName("toString names the empty-optic case rather than rendering blank sections")
    void emptyRender() {
      // Neutral label — the report serves navigation and mapping, so it is not "(no mapping)".
      assertEquals("(empty optic)", report.toString());
    }
  }

  @Nested
  @DisplayName("Defensive copy — the node list cannot be mutated after construction")
  class Immutability {

    @Test
    @DisplayName("the report copies its input list, so external mutation does not leak in")
    void defensiveCopy() {
      final var mutable = new ArrayList<OpticNode>();
      mutable.add(new Mapped("a", "a"));
      final var report = new OpticReport(mutable);
      mutable.clear();
      assertEquals(1, report.nodes().size(), "report must not reflect post-construction mutation of the source list");
    }

    @Test
    @DisplayName("nodes() is unmodifiable")
    void unmodifiableNodes() {
      final var report = new OpticReport(List.of(new Mapped("a", "a")));
      assertThrows(UnsupportedOperationException.class, () -> report.nodes().add(new Mapped("b", "b")));
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
          new Mapped("firstName", "givenName"),
          new Transformed("birthDate", "birthDate", "String", "LocalDate"),
          new Skipped("id", Reason.DROPPED)
        )
      );
      final var text = report.toString();
      assertTrue(text.contains("Mapped:"), text);
      assertTrue(text.contains("firstName"), text);
      assertTrue(text.contains("→ givenName"), text);
      assertTrue(text.contains("Transformations:"), text);
      assertTrue(text.contains("birthDate(String) → LocalDate"), text);
      assertTrue(text.contains("Skipped:"), text);
      assertTrue(text.contains("(ignored)"), text);
    }

    @Test
    @DisplayName("a navigation report renders each hop as a padded, headingless line")
    void navigationRender() {
      final var report = new OpticReport(List.of(new Traverse("departments", "collection"), new Focus("name")));
      assertEquals("Traverse: departments (collection)\nFocus:    name", report.toString());
    }

    @Test
    @DisplayName("each skip reason renders its human label")
    void reasonLabels() {
      final var report = new OpticReport(
        List.of(new Skipped("a", Reason.DROPPED), new Skipped("b", Reason.MISSING_SOURCE))
      );
      final var text = report.toString();
      assertTrue(text.contains("(ignored)"), text);
      assertTrue(text.contains("(missing source)"), text);
    }

    @Test
    @DisplayName("an unused source renders under its own section")
    void unusedSourceRender() {
      final var report = new OpticReport(List.of(new Mapped("name", "name"), new UnusedSource("legacyId")));
      final var text = report.toString();
      assertTrue(text.contains("Unused sources:"), text);
      assertTrue(text.contains("• legacyId"), text);
    }
  }

  @Nested
  @DisplayName("Golden render — the exact visual output, pinned so it cannot silently rot")
  class Golden {

    @Test
    @DisplayName("a mapping report aligns the left column across every section so all arrows line up")
    void mappingGolden() {
      final var report = new OpticReport(
        List.of(
          new Mapped("firstName", "givenName"),
          new Mapped("address.city", "city"),
          new Transformed("birthDate", "birthDate", "String", "LocalDate"),
          new Skipped("id", Reason.DROPPED)
        )
      );
      // The widest left cell is "birthDate(String)" (17), so every marker, field, and → / ( aligns
      // to that column — across Mapped, Skipped, and Transformations, not just within a section.
      final var expected = String.join(
        "\n",
        "Mapped:",
        "  ✓ firstName" + " ".repeat(9) + "→ givenName",
        "  ✓ address.city" + " ".repeat(6) + "→ city",
        "",
        "Skipped:",
        "  • id" + " ".repeat(16) + "(ignored)",
        "",
        "Transformations:",
        "  • birthDate(String) → LocalDate"
      );
      assertEquals(expected, report.toString());
    }

    @Test
    @DisplayName("a four-section report aligns Mapped, Skipped, Transformations, and Unused sources to one column")
    void fourSectionGolden() {
      final var report = new OpticReport(
        List.of(
          new Mapped("firstName", "givenName"),
          new Skipped("id", Reason.DROPPED),
          new Transformed("birthDate", "birthDate", "String", "LocalDate"),
          new UnusedSource("legacyId")
        )
      );
      // Section order is fixed (Mapped → Skipped → Transformations → Unused sources); the unused
      // row
      // carries no right cell, so it is emitted without trailing padding.
      final var expected = String.join(
        "\n",
        "Mapped:",
        "  ✓ firstName" + " ".repeat(9) + "→ givenName",
        "",
        "Skipped:",
        "  • id" + " ".repeat(16) + "(ignored)",
        "",
        "Transformations:",
        "  • birthDate(String) → LocalDate",
        "",
        "Unused sources:",
        "  • legacyId"
      );
      assertEquals(expected, report.toString());
    }

    @Test
    @DisplayName("a navigation report renders each hop as a label-aligned line")
    void navigationGolden() {
      final var report = new OpticReport(
        List.of(new Traverse("departments", "collection"), new Traverse("teams", "collection"), new Focus("name"))
      );
      final var expected = String.join(
        "\n",
        "Traverse: departments (collection)",
        "Traverse: teams (collection)",
        "Focus:    name"
      );
      assertEquals(expected, report.toString());
    }

    @Test
    @DisplayName(
      "a mixed report (mapping rows + hops) separates the section from the hops with a single blank line and no trailing blank"
    )
    void mixedReportRender() {
      // A mapping telescope further navigated (e.g. map(A, B).field(B::x)) yields both Rows and
      // Hops.
      final var report = new OpticReport(List.of(new Mapped("a", "a"), new Focus("x")));
      assertEquals("Mapped:\n  ✓ a → a\n\nFocus:    x", report.toString());
    }
  }
}
