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
 * Unit tests that drive {@link FocusProcessor} in isolation through the shared {@link
 * ProcessorHarness}. Each test compiles a single record source string with the processor wired in,
 * then asserts on either the captured generated source or the compiler diagnostics.
 */
class FocusProcessorTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compile(new FocusProcessor(), sources);
  }

  @Nested
  @DisplayName("Happy path — top-level record")
  class HappyPath {

    @Test
    @DisplayName("generates a <Record>Focus class with one lens constant per component")
    void generatesFocusClass() {
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
          public record Address(String city) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      assertTrue(compilation.errors().isEmpty(), () -> "unexpected errors: " + compilation.errorMessages());

      final var generated = compilation.generated().get("demo.PersonFocus");
      assertNotNull(generated, () -> "PersonFocus not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public final class PersonFocus"), generated);
      assertTrue(generated.contains("import org.telescope.Telescope;"), generated);
      // One typed lens constant per record component, with the field name preserved. The processor
      // emits TypeMirror.toString() for the field type, which is the fully-qualified name.
      assertTrue(generated.contains("public static final Telescope<Person, java.lang.String> name ="), generated);
      assertTrue(generated.contains("public static final Telescope<Person, demo.Address> address ="), generated);
      assertTrue(generated.contains("Telescope.lens(Person::name,"), generated);
      assertTrue(generated.contains("Telescope.lens(Person::address,"), generated);
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
      final var generated = compilation.generated().get("demo.PairFocus");
      assertNotNull(generated, () -> "PairFocus not generated; saw " + compilation.generated().keySet());

      // For the 'left' lens: new Pair(v, s.right())
      assertTrue(generated.contains("new Pair(v, s.right())"), generated);
      // For the 'right' lens: new Pair(s.left(), v)
      assertTrue(generated.contains("new Pair(s.left(), v)"), generated);
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
        compilation.generated().containsKey("demo.NotARecordFocus"),
        "no Focus class should be generated for a rejected type"
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
      assertFalse(
        compilation.generated().containsKey("demo.InnerFocus"),
        "no Focus class should be generated for a rejected nested record"
      );
    }
  }

  @Nested
  @DisplayName("Primitive boxing — boxedType()")
  class PrimitiveBoxing {

    @Test
    @DisplayName("int component surfaces as Telescope<..., Integer>")
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
      final var generated = compilation.generated().get("demo.AgeFocus");
      assertNotNull(generated, () -> "AgeFocus not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public static final Telescope<Age, Integer> age ="), generated);
      // The primitive name must not leak into the reference-typed Telescope parameter.
      assertFalse(generated.contains("Telescope<Age, int>"), generated);
    }

    @Test
    @DisplayName("every primitive component is mapped to its wrapper type")
    void allPrimitivesAreBoxed() {
      final var compilation = compile(
        source(
          "demo.Primitives",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public record Primitives(
              boolean b, byte by, short sh, int i, long l, char c, float f, double d) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.PrimitivesFocus");
      assertNotNull(generated, () -> "PrimitivesFocus not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("Telescope<Primitives, Boolean> b ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Byte> by ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Short> sh ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Integer> i ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Long> l ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Character> c ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Float> f ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Double> d ="), generated);
    }
  }

  @Nested
  @DisplayName("Compile-time traversal constants — each<Component>")
  class TraversalConstants {

    @Test
    @DisplayName("a List component gets an each<Component> traversal with the element type baked in")
    void listComponentGeneratesEach() {
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
          public record Member(String id) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.TeamFocus");
      assertNotNull(generated, () -> "TeamFocus not generated; saw " + compilation.generated().keySet());

      // The list lens stays...
      assertTrue(generated.contains("Telescope<Team, java.util.List<demo.Member>> members ="), generated);
      // ...plus a traversal constant that descends into elements, element type baked in.
      assertTrue(generated.contains("public static final Telescope<Team, demo.Member> eachMembers ="), generated);
      assertTrue(generated.contains("members.<demo.Member>each();"), generated);
      // A non-collection component gets no each constant.
      assertFalse(generated.contains("eachName"), generated);
    }

    @Test
    @DisplayName("Map yields its value type (keys preserved) and Optional yields its element")
    void mapAndOptionalGenerateEach() {
      final var compilation = compile(
        source(
          "demo.Bag",
          """
          package demo;
          import java.util.Map;
          import java.util.Optional;
          import org.telescope.annotations.Focus;
          @Focus
          public record Bag(Map<String, demo.Member> byId, Optional<demo.Member> primary) {}
          """
        ),
        source(
          "demo.Member",
          """
          package demo;
          public record Member(String id) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.BagFocus");
      assertNotNull(generated, () -> "BagFocus not generated; saw " + compilation.generated().keySet());

      // Map<String, Member> -> traversal over Member (the value type).
      assertTrue(generated.contains("public static final Telescope<Bag, demo.Member> eachById ="), generated);
      assertTrue(generated.contains("byId.<demo.Member>each();"), generated);
      // Optional<Member> -> traversal over Member.
      assertTrue(generated.contains("public static final Telescope<Bag, demo.Member> eachPrimary ="), generated);
      assertTrue(generated.contains("primary.<demo.Member>each();"), generated);
    }
  }
}
