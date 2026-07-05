package io.github.eschizoid.telescope.introspection;

import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.introspection.OpticNode.Mapped;
import io.github.eschizoid.telescope.introspection.OpticNode.Reason;
import io.github.eschizoid.telescope.introspection.OpticNode.Skipped;
import io.github.eschizoid.telescope.introspection.OpticNode.Transformed;
import io.github.eschizoid.telescope.introspection.OpticNode.UnusedSource;
import java.util.List;
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
      assertEquals(List.of(new Mapped("name", "name"), new Mapped("city", "city")), report.mapped());
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
        report.mapped().contains(new Mapped("firstName", "givenName")),
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
        report.transformations().contains(new Transformed("count", "count", "String", "Integer")),
        () -> "expected the typed transform; got " + report.transformations()
      );
    }
  }

  @Nested
  @DisplayName("Auto cross-type field — boxing")
  class AutoCrossType {

    record BoxSource(int count) {}

    record BoxTarget(Integer count) {}

    @Test
    @DisplayName("a same-name field whose types differ (int → Integer) auto-resolves to a Transformed row")
    void autoBoxingIsTransformed() {
      final var report = Telescope.mapper(BoxSource.class, BoxTarget.class).explain();
      assertTrue(
        report.transformations().contains(new Transformed("count", "count", "int", "Integer")),
        () -> "expected an auto boxing transform; got " + report.transformations()
      );
      assertTrue(
        report.mapped().isEmpty(),
        () -> "a boxing pair is a transform, not a same-typed Mapped; got " + report.mapped()
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
        report.mapped().contains(new Mapped("name", "name")),
        () -> "name should still map; got " + report.mapped()
      );
    }
  }

  @Nested
  @DisplayName("N-level nested mapping — dotted paths")
  class Nested3Level {

    record Address(String city, String zip) {}

    record AddressDto(String city, String zip) {}

    record Customer(String name, Address address) {}

    record CustomerDto(String name, AddressDto address) {}

    record Order(String id, Customer customer) {}

    record OrderDto(String id, CustomerDto customer) {}

    @Test
    @DisplayName("a 3-level auto mapper recurses into nested fields with dotted paths, not a single row")
    void dottedPathsToThreeLevels() {
      final var report = Telescope.mapper(Order.class, OrderDto.class).explain();
      assertTrue(report.mapped().contains(new Mapped("id", "id")), report::toString);
      assertTrue(report.mapped().contains(new Mapped("customer.name", "customer.name")), report::toString);
      assertTrue(
        report.mapped().contains(new Mapped("customer.address.city", "customer.address.city")),
        report::toString
      );
      assertTrue(
        report.mapped().contains(new Mapped("customer.address.zip", "customer.address.zip")),
        report::toString
      );
      // The nested record field is NOT emitted as a single opaque row — we descended into it.
      assertTrue(
        report
          .transformations()
          .stream()
          .noneMatch(t -> t.to().equals("customer")),
        () -> "customer should be recursed, not a single Transformed row: " + report.transformations()
      );
    }
  }

  @Nested
  @DisplayName("Self-referential (cyclic) type graph")
  class Cyclic {

    record Node(String value, Node next) {}

    record NodeDto(String value, NodeDto next) {}

    @Test
    @DisplayName("explain() on a self-referential mapper terminates — the cycle severs, no StackOverflow")
    void cyclicExplainTerminates() {
      // The dotted-path walk (collectNested) must sever Node → NodeDto at its second encounter via
      // the seen-guard; without it this recurses forever. Assert it returns and records the scalar.
      final var report = Telescope.mapper(Node.class, NodeDto.class).explain();
      assertTrue(report.mapped().contains(new Mapped("value", "value")), report::toString);
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
      assertTrue(report.mapped().contains(new Mapped("name", "name")), report::toString);
    }

    @Test
    @DisplayName("a value-only hook (beforeForward / afterForward) preserves the explain trail")
    void hookWrappedMapperKeepsTrail() {
      final var base = Telescope.mapperForward(NarrowSource.class, WideTarget.class);
      // A pre/post value hook does not change the field mapping — explain() must stay populated.
      final var afterHook = base.afterForward(t -> t).explain();
      assertTrue(afterHook.mapped().contains(new Mapped("name", "name")), afterHook::toString);
      assertTrue(afterHook.skipped().contains(new Skipped("region", Reason.MISSING_SOURCE)), afterHook::toString);
      final var beforeHook = base.beforeForward(s -> s).explain();
      assertTrue(beforeHook.mapped().contains(new Mapped("name", "name")), beforeHook::toString);
    }

    @Test
    @DisplayName("a source field with no consumer is an UnusedSource on a forward mapper")
    void unmappedSourceIsUnusedSource() {
      final var mapper = Telescope.mapperForward(WideSource.class, NarrowTarget.class);
      final var report = mapper.explain();
      assertTrue(
        report.unusedSources().contains(new UnusedSource("legacyId")),
        () -> "expected legacyId as an unused source; got " + report.unusedSources()
      );
      assertTrue(
        report.skipped().isEmpty(),
        () -> "an unused source is not a target-side skip; got " + report.skipped()
      );
    }
  }

  @Nested
  @DisplayName("Constant / computed target — populated, not missing")
  class ConstantAndCompute {

    record CtSource(String name) {}

    record CtTarget(String name, String status) {}

    @Test
    @DisplayName("a constant-populated target field is not reported as a missing-source skip")
    void constantFieldIsNotSkipped() {
      final var mapper = Telescope.mapperForward(CtSource.class, CtTarget.class, constant(CtTarget::status, "ACTIVE"));
      final var report = mapper.explain();
      assertTrue(
        report
          .skipped()
          .stream()
          .noneMatch(s -> s.field().equals("status")),
        () -> "status is populated by a constant row, not missing; got " + report.skipped()
      );
    }
  }
}
