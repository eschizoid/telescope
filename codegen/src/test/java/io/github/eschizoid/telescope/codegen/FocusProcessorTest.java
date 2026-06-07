package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          public record Person(String name, demo.Address address) {}
          """
        ),
        source(
          "demo.Address",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          public record Address(String city) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.PersonPath");
      assertNotNull(generated, () -> "PersonPath not generated; saw " + compilation.generated().keySet());

      // Parameterized class + the import header are emitted by writeInstanceClass.
      assertTrue(generated.contains("public final class PersonPath<R>"), generated);
      assertTrue(generated.contains("import io.github.eschizoid.telescope.Telescope;"), generated);

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
          import io.github.eschizoid.telescope.annotations.Focus;
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
          import io.github.eschizoid.telescope.annotations.Focus;
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
          import io.github.eschizoid.telescope.annotations.Focus;
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
          import io.github.eschizoid.telescope.annotations.Focus;
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
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          public record Team(String name, List<demo.Member> members) {}
          """
        ),
        source(
          "demo.Member",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Focus;
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
      // each() returns the element's Path (Member is a record). The body uses the typed
      // Telescope.asList(path).each() factory — no runtime container dispatch, all lattice.
      assertTrue(step.contains("public demo.MemberPath<R> each()"), step);
      assertTrue(step.contains("Telescope.<R, demo.Member>asList(path).each()"), step);

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
          import io.github.eschizoid.telescope.annotations.Focus;
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
    @DisplayName("Bridge hop: a record with @Focus + @Bridge gets as<Target>() returning the target's Path")
    void bridgeHopReturnsTargetPath() {
      final var compilation = compile(
        source(
          "demo.Entity",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          @Bridge(demo.Dto.class)
          public record Entity(String id, String email) {}
          """
        ),
        source(
          "demo.Dto",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          public record Dto(String id, String email) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.EntityPath");
      assertNotNull(generated, () -> "EntityPath not generated; saw " + compilation.generated().keySet());

      // Target is itself navigable (@Focus'd) → return its Path.
      assertTrue(generated.contains("public demo.DtoPath<R> asDto()"), generated);
      assertTrue(generated.contains("new demo.DtoPath<>(path.then(EntityBridge.BRIDGE))"), generated);
    }

    @Test
    @DisplayName("Bridge hop: target without @Focus gets terminal Telescope<R, Target>")
    void bridgeHopTerminalWhenTargetIsNotNavigable() {
      final var compilation = compile(
        source(
          "demo.Entity",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          @Bridge(demo.Plain.class)
          public record Entity(String id) {}
          """
        ),
        source(
          "demo.Plain",
          """
          package demo;
          public record Plain(String id) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.EntityPath");
      assertNotNull(generated, () -> "EntityPath not generated; saw " + compilation.generated().keySet());

      // Target isn't @Focus'd → return terminal Telescope, not a Path.
      assertTrue(generated.contains("public Telescope<R, demo.Plain> asPlain()"), generated);
      assertTrue(generated.contains("return path.then(EntityBridge.BRIDGE);"), generated);
      assertFalse(generated.contains("PlainPath"), generated);
    }

    @Test
    @DisplayName("No @Bridge means no as<Target>() method")
    void noBridgeNoHop() {
      final var compilation = compile(
        source(
          "demo.PlainRec",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          public record PlainRec(String id) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.PlainRecPath");
      assertNotNull(generated, () -> "PlainRecPath not generated; saw " + compilation.generated().keySet());

      // No @Bridge → no bridge hop (no reference to a <Source>Bridge.BRIDGE constant).
      assertFalse(generated.contains("Bridge.BRIDGE"), () -> "unexpected bridge hop in: " + generated);
    }

    @Test
    @DisplayName("emits a sibling <X>Telescope holder with one typed Telescope constant per component (ADR-0006)")
    void generatesTelescopeHolder() {
      final var compilation = compile(
        source(
          "demo.Person",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          public record Person(String name, int age) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.PersonTelescope");
      assertNotNull(holder, () -> "PersonTelescope not generated; saw " + compilation.generated().keySet());

      // Holder is a top-level public final class in the user's package, no instances permitted.
      assertTrue(holder.contains("public final class PersonTelescope"), holder);
      assertTrue(holder.contains("private PersonTelescope() {}"), holder);

      // One static-final constant per component, with the field type as the Telescope's second
      // type parameter (primitive `int` is boxed to Integer).
      assertTrue(holder.contains("public static final Telescope<Person, String> name"), holder);
      assertTrue(holder.contains("public static final Telescope<Person, Integer> age"), holder);

      // Each constant uses Telescope.lens(...) with the same canonical-setter expression the Path
      // navigator would emit.
      assertTrue(holder.contains("Telescope.lens(Person::name,"), holder);
      assertTrue(holder.contains("Telescope.lens(Person::age,"), holder);

      // Standard javadoc and Telescope import.
      assertTrue(holder.contains("import io.github.eschizoid.telescope.Telescope;"), holder);
      assertTrue(holder.contains("Per-component Telescope constants for runtime hybrid dispatch"), holder);
    }

    @Test
    @DisplayName("a container-shaped component surfaces as a raw Telescope<X, Container<E>> constant (not lifted)")
    void telescopeHolderForContainerComponent() {
      final var compilation = compile(
        source(
          "demo.Bag",
          """
          package demo;
          import java.util.List;
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          public record Bag(List<String> tags) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.BagTelescope");
      assertNotNull(holder, () -> "BagTelescope not generated; saw " + compilation.generated().keySet());

      // Raw container lens on the holder — the Path's container step lifts; the holder does not.
      // Consumers compose via .then(...) if they want element-level navigation.
      assertTrue(holder.contains("public static final Telescope<Bag, List<String>> tags"), holder);
      assertTrue(holder.contains("import java.util.List;"), holder);
    }

    @Test
    @DisplayName("a sub-@Focus record component surfaces as Telescope<X, SubRecord> — terminal-to-record, not composed")
    void telescopeHolderForSubFocusComponent() {
      final var compilation = compile(
        source(
          "demo.User",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          public record User(String name, demo.Address address) {}
          """
        ),
        source(
          "demo.Address",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          public record Address(String city) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var holder = compilation.generated().get("demo.UserTelescope");
      assertNotNull(holder, () -> "UserTelescope not generated; saw " + compilation.generated().keySet());

      // Sub-record component is just a typed lens to the sub-value; no composition with the
      // sub-record's own holder (consumers compose via .then(...) themselves).
      assertTrue(holder.contains("public static final Telescope<User, demo.Address> address"), holder);
      assertTrue(holder.contains("Telescope.lens(User::address,"), holder);
    }

    @Test
    @DisplayName("a component with wildcard-bound generics is rejected with a precise diagnostic (ADR-0006 §7)")
    void wildcardGenericsRejected() {
      final var compilation = compile(
        source(
          "demo.Wild",
          """
          package demo;
          import java.util.List;
          import io.github.eschizoid.telescope.annotations.Focus;
          @Focus
          public record Wild(List<? extends Comparable<?>> values) {}
          """
        )
      );

      assertFalse(compilation.success(), "compilation should have failed for wildcard-bound generics");
      assertTrue(
        compilation.hasError("cannot emit metadata constant"),
        () -> "expected wildcard diagnostic; saw " + compilation.errorMessages()
      );
      assertTrue(
        compilation.hasError("wildcard or self-referential bounds"),
        () -> "expected wildcard diagnostic; saw " + compilation.errorMessages()
      );
      assertFalse(
        compilation.generated().containsKey("demo.WildTelescope"),
        "no Telescope holder should be generated for a rejected type"
      );
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
          import io.github.eschizoid.telescope.annotations.Focus;
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
