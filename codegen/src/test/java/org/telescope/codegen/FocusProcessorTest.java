package org.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.telescope.codegen.ProcessorHarness.source;

import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telescope.codegen.ProcessorHarness.Compilation;

/**
 * Drives {@link FocusProcessor} through the shared {@link ProcessorHarness}. Asserts on the shape
 * of the generated fluent navigator: a {@code <Record>Path<R>} class with a {@code start()}
 * factory, a {@code get()} terminal, and one method per component (scalar terminal /
 * sub-record-Path / container step), plus the container step classes for {@code List}/{@code
 * Map}/{@code Optional} components.
 */
class FocusProcessorTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compile(new FocusProcessor(), sources);
  }

  @Nested
  @DisplayName("Happy path — navigator class shape")
  class HappyPath {

    @Test
    @DisplayName("generates a <Record>Path<R> class with start(), get(), and one method per component")
    void generatesPathClass() {
      final var compilation = compile(
        source(
          "demo.Person",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public record Person(String name, demo.Address address) {}
          """
        ),
        source(
          "demo.Address",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public record Address(String city) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.PersonPath");
      assertNotNull(generated, () -> "PersonPath not generated; saw " + compilation.generated().keySet());

      // Parameterised class + the import header are emitted by writeInstanceClass.
      assertTrue(generated.contains("public final class PersonPath<R>"), generated);
      assertTrue(generated.contains("import org.telescope.Telescope;"), generated);

      // start() returns PersonPath<Person> rooted at Telescope.of(Person.class).
      assertTrue(generated.contains("public static PersonPath<Person> start()"), generated);
      assertTrue(generated.contains("Telescope.of(Person.class)"), generated);

      // get() exposes the current path as a Telescope.
      assertTrue(generated.contains("public Telescope<R, Person> get()"), generated);

      // Scalar component: terminal Telescope<R, String> method built from Telescope.lens.
      assertTrue(generated.contains("public Telescope<R, String> name()"), generated);
      assertTrue(generated.contains("Telescope.lens(Person::name,"), generated);

      // Sub-record component: returns the sub-record's Path<R>.
      assertTrue(generated.contains("public demo.AddressPath<R> address()"), generated);
      assertTrue(generated.contains("new demo.AddressPath<>"), generated);
    }

    @Test
    @DisplayName("the canonical-constructor setter rebuilds every component, swapping only the focused one")
    void setterRebuildsAllComponents() {
      final var compilation = compile(
        source(
          "demo.Pair",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public record Pair(String left, String right) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.PairPath");
      assertNotNull(generated, () -> "PairPath not generated; saw " + compilation.generated().keySet());

      // For the 'left' navigator: (s, v) -> new Pair(v, s.right())
      assertTrue(generated.contains("(s, v) -> new Pair(v, s.right())"), generated);
      // For the 'right' navigator: (s, v) -> new Pair(s.left(), v)
      assertTrue(generated.contains("(s, v) -> new Pair(s.left(), v)"), generated);
    }
  }

  @Nested
  @DisplayName("Rejections — guards raise compile errors")
  class Rejections {

    @Test
    @DisplayName("@Focus on a non-record type is an error")
    void nonRecordIsRejected() {
      final var compilation = compile(
        source(
          "demo.NotARecord",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public class NotARecord {}
          """
        )
      );

      assertFalse(compilation.success(), "compilation should have failed for a non-record @Focus");
      assertTrue(
        compilation.hasError("@Focus is only supported on records"),
        () -> "expected non-record diagnostic; saw " + compilation.errorMessages()
      );
      assertFalse(
        compilation.generated().containsKey("demo.NotARecordPath"),
        "no Path class should be generated for a rejected type"
      );
    }

    @Test
    @DisplayName("@Focus on a nested (non-top-level) record is an error")
    void nestedRecordIsRejected() {
      final var compilation = compile(
        source(
          "demo.Outer",
          """
          package demo;
          import org.telescope.annotations.Focus;
          public class Outer {
            @Focus
            public record Inner(String value) {}
          }
          """
        )
      );

      assertFalse(compilation.success(), "compilation should have failed for a nested @Focus record");
      assertTrue(
        compilation.hasError("@Focus is only supported on top-level records"),
        () -> "expected nested-record diagnostic; saw " + compilation.errorMessages()
      );
    }
  }

  @Nested
  @DisplayName("Primitive boxing")
  class PrimitiveBoxing {

    @Test
    @DisplayName("int component surfaces as Telescope<R, Integer> on the navigator method")
    void intIsBoxedToInteger() {
      final var compilation = compile(
        source(
          "demo.Age",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public record Age(int age) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.AgePath");
      assertNotNull(generated, () -> "AgePath not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public Telescope<R, Integer> age()"), generated);
      // The primitive name must not leak into the reference-typed Telescope parameter.
      assertFalse(generated.contains("Telescope<R, int>"), generated);
    }
  }

  @Nested
  @DisplayName("Container steps — List/Set/Iterable, Map, Optional")
  class ContainerSteps {

    @Test
    @DisplayName("a List<Record> component emits a Step whose each() returns the element's Path<R>")
    void listOfRecordsEachReturnsElementPath() {
      final var compilation = compile(
        source(
          "demo.Team",
          """
          package demo;
          import java.util.List;
          import org.telescope.annotations.Focus;
          @Focus
          public record Team(String name, List<demo.Member> members) {}
          """
        ),
        source(
          "demo.Member",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public record Member(String id) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());

      // Step class is its own top-level type, named <Record><Cap><Component>Step.
      final var step = compilation.generated().get("demo.TeamMembersStep");
      assertNotNull(step, () -> "TeamMembersStep not generated; saw " + compilation.generated().keySet());
      assertTrue(step.contains("public final class TeamMembersStep<R>"), step);
      assertTrue(step.contains("public Telescope<R, List<demo.Member>> get()"), step);
      // each() returns the element's Path (Member is a record).
      assertTrue(step.contains("public demo.MemberPath<R> each()"), step);
      assertTrue(step.contains("path.<demo.Member>each()"), step);

      // The Path itself routes the members() method to the Step.
      final var path = compilation.generated().get("demo.TeamPath");
      assertNotNull(path, () -> "TeamPath not generated; saw " + compilation.generated().keySet());
      assertTrue(path.contains("public TeamMembersStep<R> members()"), path);
    }

    @Test
    @DisplayName("a List<Scalar> component emits a Step whose each() returns a terminal Telescope")
    void listOfScalarsEachReturnsTerminal() {
      final var compilation = compile(
        source(
          "demo.Bag",
          """
          package demo;
          import java.util.List;
          import org.telescope.annotations.Focus;
          @Focus
          public record Bag(List<String> tags) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var step = compilation.generated().get("demo.BagTagsStep");
      assertNotNull(step, () -> "BagTagsStep not generated; saw " + compilation.generated().keySet());
      // Scalar element → terminal Telescope<R, String>, not a Path.
      assertTrue(step.contains("public Telescope<R, String> each()"), step);
    }

    @Test
    @DisplayName("Map values use eachValue() (keys preserved); Optional uses whenPresent()")
    void mapAndOptionalUseDistinctStepMethods() {
      final var compilation = compile(
        source(
          "demo.Bag",
          """
          package demo;
          import java.util.Map;
          import java.util.Optional;
          import org.telescope.annotations.Focus;
          @Focus
          public record Bag(Map<String, String> labels, Optional<String> note) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());

      final var labelsStep = compilation.generated().get("demo.BagLabelsStep");
      assertNotNull(labelsStep, () -> "BagLabelsStep not generated; saw " + compilation.generated().keySet());
      assertTrue(labelsStep.contains("public Telescope<R, String> eachValue()"), labelsStep);

      final var noteStep = compilation.generated().get("demo.BagNoteStep");
      assertNotNull(noteStep, () -> "BagNoteStep not generated; saw " + compilation.generated().keySet());
      assertTrue(noteStep.contains("public Telescope<R, String> whenPresent()"), noteStep);
    }
  }
}
