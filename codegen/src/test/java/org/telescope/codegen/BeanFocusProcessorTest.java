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
 * Drives {@link BeanFocusProcessor} through the shared {@link ProcessorHarness}. Covers both
 * rebuild strategies (static {@code builder()} and no-arg constructor + setters), primitive boxing,
 * and every guard that raises a compile error.
 */
class BeanFocusProcessorTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compile(new BeanFocusProcessor(), sources);
  }

  @Nested
  @DisplayName("Happy path — rebuild strategies")
  class HappyPath {

    @Test
    @DisplayName("builder() POJO: lens setter rebuilds via builder(), swapping only the focused property")
    void builderStrategy() {
      final var compilation = compile(
        source(
          "demo.BuilderPojo",
          """
          package demo;
          import org.telescope.annotations.BeanFocus;
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
      final var generated = compilation.generated().get("demo.BuilderPojoFocus");
      assertNotNull(generated, () -> "BuilderPojoFocus not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public static final Telescope<BuilderPojo, java.lang.String> id ="), generated);
      assertTrue(generated.contains("Telescope.lens(BuilderPojo::getId,"), generated);
      assertTrue(generated.contains("BuilderPojo.builder()"), generated);
      assertTrue(generated.contains(".build()"), generated);
      // The focused property takes v; siblings are read back from p.
      assertTrue(generated.contains("id(v)"), generated);
      assertTrue(generated.contains("email(v)"), generated);
      assertTrue(generated.contains("email(p.getEmail())"), generated);
    }

    @Test
    @DisplayName("setter POJO: lens setter rebuilds via no-arg ctor + setX")
    void setterStrategy() {
      final var compilation = compile(
        source(
          "demo.SetterPojo",
          """
          package demo;
          import org.telescope.annotations.BeanFocus;
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
      final var generated = compilation.generated().get("demo.SetterPojoFocus");
      assertNotNull(generated, () -> "SetterPojoFocus not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("new SetterPojo()"), generated);
      assertTrue(generated.contains("c.setId(v)"), generated);
      assertTrue(generated.contains("c.setEmail(v)"), generated);
      assertTrue(generated.contains("return c;"), generated);
    }

    @Test
    @DisplayName("int property surfaces as Telescope<..., Integer>")
    void primitiveIsBoxed() {
      final var compilation = compile(
        source(
          "demo.Counter",
          """
          package demo;
          import org.telescope.annotations.BeanFocus;
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
      final var generated = compilation.generated().get("demo.CounterFocus");
      assertNotNull(generated, () -> "CounterFocus not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("Telescope<Counter, Integer> count ="), generated);
      assertFalse(generated.contains("Telescope<Counter, int>"), generated);
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
          import org.telescope.annotations.BeanFocus;
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
          import org.telescope.annotations.BeanFocus;
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
          import org.telescope.annotations.BeanFocus;
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
          import org.telescope.annotations.BeanFocus;
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

    @Test
    @DisplayName("no-arg constructor but a missing setter is an error")
    void missingSetterIsRejected() {
      final var compilation = compile(
        source(
          "demo.HalfBean",
          """
          package demo;
          import org.telescope.annotations.BeanFocus;
          @BeanFocus
          public class HalfBean {
            private String a = "x";
            public HalfBean() {}
            public String getA() { return a; }
          }
          """
        )
      );

      assertFalse(compilation.success(), "a getter-only @BeanFocus class should fail");
      assertTrue(
        compilation.hasError("no setter for property 'a'"),
        () -> "expected missing-setter diagnostic; saw " + compilation.errorMessages()
      );
    }
  }
}
