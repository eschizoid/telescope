package com.github.eschizoid.telescope.codegen;

import static com.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link BeanFocusProcessor} through the shared {@link ProcessorHarness}. Asserts on the
 * shape of the generated fluent navigator: {@code <Pojo>Path<R>} with {@code start()}, {@code
 * get()}, and per-property methods (scalar terminal / sub-bean-Path / container step), for both
 * rebuild strategies (static {@code builder()} and no-arg constructor + setters), plus the guards.
 */
class BeanFocusProcessorTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compile(new BeanFocusProcessor(), sources);
  }

  @Nested
  @DisplayName("Happy path — navigator shape across rebuild strategies")
  class HappyPath {

    @Test
    @DisplayName("builder() POJO: navigator method rebuilds via builder(), swapping only the focused property")
    void builderStrategy() {
      final var compilation = compile(
        source(
          "demo.BuilderPojo",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class BuilderPojo {
            private final String id;
            private final String email;
            private BuilderPojo(String id, String email) { this.id = id; this.email = email; }
            public String getId() { return id; }
            public String getEmail() { return email; }
            public static Builder builder() { return new Builder(); }
            public static final class Builder {
              private String id;
              private String email;
              public Builder id(String id) { this.id = id; return this; }
              public Builder email(String email) { this.email = email; return this; }
              public BuilderPojo build() { return new BuilderPojo(id, email); }
            }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.BuilderPojoPath");
      assertNotNull(generated, () -> "BuilderPojoPath not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public final class BuilderPojoPath<R>"), generated);
      assertTrue(generated.contains("public static BuilderPojoPath<BuilderPojo> start()"), generated);
      assertTrue(generated.contains("public Telescope<R, String> id()"), generated);
      assertTrue(generated.contains("Telescope.lens(BuilderPojo::getId,"), generated);
      assertTrue(generated.contains("BuilderPojo.builder()"), generated);
      assertTrue(generated.contains(".build()"), generated);
      // The focused property takes v; siblings are read back from p.
      assertTrue(generated.contains("id(v)"), generated);
      assertTrue(generated.contains("email(v)"), generated);
      assertTrue(generated.contains("email(p.getEmail())"), generated);
    }

    @Test
    @DisplayName("setter POJO: navigator method rebuilds via no-arg ctor + setX")
    void setterStrategy() {
      final var compilation = compile(
        source(
          "demo.SetterPojo",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class SetterPojo {
            private String id;
            private String email;
            public SetterPojo() {}
            public String getId() { return id; }
            public String getEmail() { return email; }
            public void setId(String id) { this.id = id; }
            public void setEmail(String email) { this.email = email; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.SetterPojoPath");
      assertNotNull(generated, () -> "SetterPojoPath not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public final class SetterPojoPath<R>"), generated);
      assertTrue(generated.contains("new SetterPojo()"), generated);
      assertTrue(generated.contains("c.setId(v)"), generated);
      assertTrue(generated.contains("c.setEmail(v)"), generated);
      assertTrue(generated.contains("return c;"), generated);
    }

    @Test
    @DisplayName("int property surfaces as Telescope<R, Integer> on the navigator method")
    void primitiveIsBoxed() {
      final var compilation = compile(
        source(
          "demo.Counter",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Counter {
            private int count;
            public Counter() {}
            public int getCount() { return count; }
            public void setCount(int count) { this.count = count; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.CounterPath");
      assertNotNull(generated, () -> "CounterPath not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public Telescope<R, Integer> count()"), generated);
      assertFalse(generated.contains("Telescope<R, int>"), generated);
    }

    @Test
    @DisplayName("a List property emits a Step whose each() returns a terminal Telescope")
    void listOfScalarsEachReturnsTerminal() {
      final var compilation = compile(
        source(
          "demo.Roster",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Roster {
            private java.util.List<String> names;
            public Roster() {}
            public java.util.List<String> getNames() { return names; }
            public void setNames(java.util.List<String> names) { this.names = names; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var step = compilation.generated().get("demo.RosterNamesStep");
      assertNotNull(step, () -> "RosterNamesStep not generated; saw " + compilation.generated().keySet());
      assertTrue(step.contains("public final class RosterNamesStep<R>"), step);
      assertTrue(step.contains("public Telescope<R, String> each()"), step);

      final var path = compilation.generated().get("demo.RosterPath");
      assertNotNull(path);
      assertTrue(path.contains("public RosterNamesStep<R> names()"), path);
    }

    @Test
    @DisplayName("Bridge hop: a POJO with @BeanFocus + @Bridge gets as<Target>() chaining the bridge")
    void bridgeHop() {
      final var compilation = compile(
        source(
          "demo.UserBean",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.Bridge;
          import com.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          @Bridge(demo.UserDto.class)
          public class UserBean {
            private String id;
            public UserBean() {}
            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
          }
          """
        ),
        source(
          "demo.UserDto",
          """
          package demo;
          public record UserDto(String id) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.UserBeanPath");
      assertNotNull(generated, () -> "UserBeanPath not generated; saw " + compilation.generated().keySet());

      // Target is not @Focus'd (just a record) → terminal Telescope.
      assertTrue(generated.contains("public Telescope<R, demo.UserDto> asUserDto()"), generated);
      assertTrue(generated.contains("return path.then(UserBeanBridge.BRIDGE);"), generated);
    }

    @Test
    @DisplayName("a Map property's step exposes eachValue(); an Optional property's step exposes whenPresent()")
    void mapAndOptionalUseDistinctStepMethods() {
      final var compilation = compile(
        source(
          "demo.Store",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Store {
            private java.util.Map<String, String> labels;
            private java.util.Optional<String> note;
            public Store() {}
            public java.util.Map<String, String> getLabels() { return labels; }
            public java.util.Optional<String> getNote() { return note; }
            public void setLabels(java.util.Map<String, String> labels) { this.labels = labels; }
            public void setNote(java.util.Optional<String> note) { this.note = note; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());

      final var labelsStep = compilation.generated().get("demo.StoreLabelsStep");
      assertNotNull(labelsStep, () -> "StoreLabelsStep not generated; saw " + compilation.generated().keySet());
      assertTrue(labelsStep.contains("public Telescope<R, String> eachValue()"), labelsStep);

      final var noteStep = compilation.generated().get("demo.StoreNoteStep");
      assertNotNull(noteStep, () -> "StoreNoteStep not generated; saw " + compilation.generated().keySet());
      assertTrue(noteStep.contains("public Telescope<R, String> whenPresent()"), noteStep);
    }
  }

  @Nested
  @DisplayName("Rejections — guards raise compile errors")
  class Rejections {

    @Test
    @DisplayName("@BeanFocus on a record is an error (records use @Focus)")
    void recordIsRejected() {
      final var compilation = compile(
        source(
          "demo.RecPojo",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public record RecPojo(String a) {}
          """
        )
      );

      assertFalse(compilation.success(), "a record @BeanFocus should fail");
      assertTrue(
        compilation.hasError("@BeanFocus is only supported on classes"),
        () -> "expected records-use-@Focus diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@BeanFocus on a nested class is an error")
    void nestedClassIsRejected() {
      final var compilation = compile(
        source(
          "demo.Outer",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.BeanFocus;
          public class Outer {
            @BeanFocus
            public static class Inner {
              public Inner() {}
              public String getA() { return null; }
              public void setA(String a) {}
            }
          }
          """
        )
      );

      assertFalse(compilation.success(), "a nested @BeanFocus class should fail");
      assertTrue(
        compilation.hasError("@BeanFocus is only supported on top-level classes"),
        () -> "expected top-level diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("no readable properties is an error")
    void noPropertiesIsRejected() {
      final var compilation = compile(
        source(
          "demo.Empty",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Empty {
            public Empty() {}
            public void run() {}
          }
          """
        )
      );

      assertFalse(compilation.success(), "a property-less @BeanFocus class should fail");
      assertTrue(
        compilation.hasError("has no readable properties"),
        () -> "expected no-properties diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("no builder and no no-arg constructor is an error (field injection unavailable to codegen)")
    void noStrategyIsRejected() {
      final var compilation = compile(
        source(
          "demo.Immutable",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.BeanFocus;
          @BeanFocus
          public class Immutable {
            private final String a;
            public Immutable(String a) { this.a = a; }
            public String getA() { return a; }
          }
          """
        )
      );

      assertFalse(compilation.success(), "an all-args-only @BeanFocus class should fail");
      assertTrue(
        compilation.hasError("needs a static builder() or a public no-arg constructor with setters"),
        () -> "expected no-strategy diagnostic; saw " + compilation.errorMessages()
      );
    }
  }
}
