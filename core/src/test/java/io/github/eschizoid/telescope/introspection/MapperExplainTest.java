package io.github.eschizoid.telescope.introspection;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.introspection.OpticNode.Mapped;
import io.github.eschizoid.telescope.introspection.OpticNode.Reason;
import io.github.eschizoid.telescope.introspection.OpticNode.Skipped;
import io.github.eschizoid.telescope.introspection.OpticNode.Transformed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end contract tests for {@code Mapper.explain()} — a real mapper built through {@code
 * Telescope.mapper(...)} must surface a report whose rows match the field decisions the
 * deep-mapping engine actually made. Pins that same-name fields become {@code Mapped}, renamed
 * fields become {@code Mapped} under the target name, cross-type rows become {@code Transformed},
 * and dropped source fields become {@code Skipped(DROPPED)} — and that a strict mapper skips
 * nothing.
 */
class MapperExplainTest {

  record Source(String name, String city) {}

  record Target(String name, String city) {}

  record RenameSource(String firstName, String city) {}

  record RenameTarget(String givenName, String city) {}

  record TypedSource(String count, String city) {}

  record TypedTarget(Integer count, String city) {}

  @Nested
  @DisplayName("Same-name mapping")
  class SameName {

    @Test
    @DisplayName("every same-name field is a Mapped row and the strict mapper skips nothing")
    void allMappedNoSkips() {
      final var mapper = Telescope.mapper(Source.class, Target.class);
      final var report = mapper.explain();
      assertEquals(
        java.util.List.of(new Mapped("name", "name", "name"), new Mapped("city", "city", "city")),
        report.mapped()
      );
      assertTrue(report.skipped().isEmpty(), () -> "strict mapper must skip nothing, got: " + report.skipped());
      assertTrue(report.transformations().isEmpty(), report::toString);
    }
  }

  @Nested
  @DisplayName("Renamed field")
  class Rename {

    @Test
    @DisplayName("an explicit rename row surfaces as a Mapped row under source→target names")
    void renameIsMapped() {
      final var mapper = Telescope.mapper(
        RenameSource.class,
        RenameTarget.class,
        to(RenameSource::firstName, RenameTarget::givenName)
      );
      final var report = mapper.explain();
      assertTrue(
        report.mapped().contains(new Mapped("firstName", "firstName", "givenName")),
        () -> "expected the rename row; got " + report.mapped()
      );
      assertTrue(report.skipped().isEmpty(), report::toString);
    }
  }

  @Nested
  @DisplayName("Cross-type row")
  class Typed {

    @Test
    @DisplayName("a typed-transform row surfaces as a Transformed row naming both types")
    void typedTransformIsTransformed() {
      final var mapper = Telescope.mapper(
        TypedSource.class,
        TypedTarget.class,
        to(TypedSource::count, TypedTarget::count, Integer::parseInt, String::valueOf)
      );
      final var report = mapper.explain();
      assertTrue(
        report.transformations().contains(new Transformed("count", "String", "Integer")),
        () -> "expected the typed transform; got " + report.transformations()
      );
    }
  }

  @Nested
  @DisplayName("Dropped field")
  class Dropped {

    record DropSource(String name, String legacyId) {}

    record DropTarget(String name) {}

    @Test
    @DisplayName("a drop row surfaces as Skipped(DROPPED) — the dropped source field is claimed, not mapped")
    void dropIsSkipped() {
      final var mapper = Telescope.mapper(DropSource.class, DropTarget.class, drop(DropSource::legacyId));
      final var report = mapper.explain();
      assertTrue(
        report.skipped().contains(new Skipped("legacyId", Reason.DROPPED)),
        () -> "expected the dropped field; got " + report.skipped()
      );
      assertTrue(
        report.mapped().contains(new Mapped("name", "name", "name")),
        () -> "name should still map; got " + report.mapped()
      );
    }
  }

  @Nested
  @DisplayName("Forward-only mapper — lenient skips surface with reasons")
  class Forward {

    record NarrowSource(String name) {}

    record WideTarget(String name, String region) {}

    record WideSource(String name, String legacyId) {}

    record NarrowTarget(String name) {}

    @Test
    @DisplayName("a target field with no source is Skipped(MISSING_SOURCE) on a forward mapper")
    void missingSourceIsSkipped() {
      final var mapper = Telescope.mapperForward(NarrowSource.class, WideTarget.class);
      final var report = mapper.explain();
      assertTrue(
        report.skipped().contains(new Skipped("region", Reason.MISSING_SOURCE)),
        () -> "expected region as missing source; got " + report.skipped()
      );
      assertTrue(report.mapped().contains(new Mapped("name", "name", "name")), report::toString);
    }

    @Test
    @DisplayName("a source field with no consumer is Skipped(UNMAPPED_SOURCE) on a forward mapper")
    void unmappedSourceIsSkipped() {
      final var mapper = Telescope.mapperForward(WideSource.class, NarrowTarget.class);
      final var report = mapper.explain();
      assertTrue(
        report.skipped().contains(new Skipped("legacyId", Reason.UNMAPPED_SOURCE)),
        () -> "expected legacyId as unmapped source; got " + report.skipped()
      );
    }
  }
}
