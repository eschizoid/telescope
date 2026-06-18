package io.github.eschizoid.telescope.codegen.lombok;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compile;
import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * In-process tests for {@link LombokFocusProcessor#process}. Drives the processor through the
 * in-memory {@code ProcessorHarness} so each invocation runs inside the JUnit JVM — directly
 * exercising the {@code process} body's branches (pending-set accumulation, the {@code
 * ElementKind.CLASS} gatekeeper, the {@code processingOver} last-resort emit) against synthetic
 * source code we control. Complements the file-based {@link LombokFocusProcessorTest}, which
 * compiles real Lombok-annotated fixtures through Gradle's pipeline to verify end-to-end
 * emitted-code behaviour.
 *
 * <p>Lombok's AST-patching processor isn't on the harness — it can't be, per the round-deferred
 * gotcha documented on the processor. Each fixture supplies its Lombok annotation <em>plus</em> the
 * getters/setters/builder Lombok would normally synthesise. {@code LombokFocusProcessor} only needs
 * {@code lombok.Data} / {@code @Value} / {@code @Builder} to resolve as a type element (Lombok is
 * on the test classpath) and a readable bean shape from {@code Elements#getAllMembers} — both
 * satisfied without Lombok firing.
 */
class LombokFocusProcessorHarnessTest {

  @Nested
  @DisplayName("Emits navigators for each Lombok bean trigger annotation")
  class TriggerAnnotations {

    @Test
    @DisplayName("@Data POJO with explicit accessors emits a <Pojo>Telescope navigator")
    void dataAnnotationEmitsNavigator() {
      final var compilation = compile(
        new LombokFocusProcessor(),
        source(
          "demo.DataPojo",
          """
          package demo;
          import lombok.Data;
          @Data
          public class DataPojo {
            private String email;
            public DataPojo() {}
            public String getEmail() { return email; }
            public void setEmail(String email) { this.email = email; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var navigator = compilation.generated().get("demo.DataPojoTelescope");
      assertNotNull(navigator, () -> "expected demo.DataPojoTelescope; got " + compilation.generated().keySet());
      assertTrue(navigator.contains("email"), navigator);
    }

    @Test
    @DisplayName("@Value + @Builder POJO emits a <Pojo>Telescope navigator via the builder rebuild path")
    void valueWithBuilderEmitsNavigator() {
      // Bare @Value yields an all-final-fields shape with no setters and no builder — the
      // processor's no-readable-write-strategy diagnostic fires there. @Value + @Builder is the
      // canonical Lombok pattern that produces an emittable bean (rebuild via the static
      // builder() chain) and matches the existing fixtures/ValueBuilderUser shape.
      final var compilation = compile(
        new LombokFocusProcessor(),
        source(
          "demo.ValuePojo",
          """
          package demo;
          import lombok.Value;
          import lombok.Builder;
          @Value
          @Builder
          public class ValuePojo {
            String id;
            public ValuePojo(String id) { this.id = id; }
            public String getId() { return id; }
            public static Builder builder() { return new Builder(); }
            public static final class Builder {
              private String id;
              public Builder id(String id) { this.id = id; return this; }
              public ValuePojo build() { return new ValuePojo(id); }
            }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var navigator = compilation.generated().get("demo.ValuePojoTelescope");
      assertNotNull(navigator);
      assertTrue(navigator.contains("id"), () -> "expected 'id' accessor; got: " + navigator);
    }

    @Test
    @DisplayName("@Builder POJO with explicit builder() emits a <Pojo>Telescope navigator")
    void builderAnnotationEmitsNavigator() {
      final var compilation = compile(
        new LombokFocusProcessor(),
        source(
          "demo.BuilderPojo",
          """
          package demo;
          import lombok.Builder;
          @Builder
          public class BuilderPojo {
            private final String label;
            private BuilderPojo(String label) { this.label = label; }
            public String getLabel() { return label; }
            public static Builder builder() { return new Builder(); }
            public static final class Builder {
              private String label;
              public Builder label(String label) { this.label = label; return this; }
              public BuilderPojo build() { return new BuilderPojo(label); }
            }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var navigator = compilation.generated().get("demo.BuilderPojoTelescope");
      assertNotNull(navigator);
      assertTrue(navigator.contains("label"), () -> "expected 'label' accessor; got: " + navigator);
    }

    @Test
    @DisplayName("@Value with no setters and no builder() is rejected with the no-write-strategy diagnostic")
    void bareValueIsRejectedAtEmit() {
      // Pins the negative-case contract on the AbstractTelescopeProcessor emit path: a Lombok
      // bean with all-final fields and no static builder() has no write strategy the processor
      // can drive, and the emitter must reject with a precise diagnostic rather than producing
      // a half-built navigator. Without this test the "reject" branch in emitBeanNavigator's
      // write-strategy probe is unexercised in-process.
      final var compilation = compile(
        new LombokFocusProcessor(),
        source(
          "demo.BareValue",
          """
          package demo;
          import lombok.Value;
          @Value
          public class BareValue {
            String id;
            public BareValue(String id) { this.id = id; }
            public String getId() { return id; }
          }
          """
        )
      );

      assertNull(
        compilation.generated().get("demo.BareValueTelescope"),
        () -> "bare @Value must NOT yield a navigator; saw " + compilation.generated().keySet()
      );
      assertTrue(
        compilation.hasError("needs a static builder()"),
        () -> "expected 'needs a static builder()' diagnostic; got: " + compilation.errorMessages()
      );
    }
  }

  @Nested
  @DisplayName("Edge cases — non-class targets, missing annotations, multi-property classes")
  class EdgeCases {

    @Test
    @DisplayName("class kind filter: annotations on enums/interfaces never reach emit")
    void nonClassKindIsSkipped() {
      // @lombok.Builder on a static-factory method is legal Lombok syntax but the element kind is
      // METHOD, not CLASS — the processor's `element.getKind() != ElementKind.CLASS` continue is
      // the gatekeeper. A regression that emitted for non-class targets would generate stray
      // navigators here.
      final var compilation = compile(
        new LombokFocusProcessor(),
        source(
          "demo.NotAClass",
          """
          package demo;
          import lombok.Data;
          @Data
          public interface NotAClass {
            String getEmail();
          }
          """
        )
      );

      // Assert success() too: a regression that emitted a navigator and then tripped a downstream
      // compile error would silently leave the generated() map empty, and the assertNull below
      // would pass for the wrong reason. Requiring success() means the no-emit path is what's
      // actually being exercised.
      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      assertNull(
        compilation.generated().get("demo.NotAClassTelescope"),
        () -> "interface must NOT yield a navigator; saw " + compilation.generated().keySet()
      );
    }

    @Test
    @DisplayName("multi-property @Data: navigator carries one method per property")
    void multiPropertyDataPojoEmitsAllAccessors() {
      final var compilation = compile(
        new LombokFocusProcessor(),
        source(
          "demo.MultiData",
          """
          package demo;
          import lombok.Data;
          @Data
          public class MultiData {
            private String id;
            private String email;
            private int age;
            public MultiData() {}
            public String getId() { return id; }
            public String getEmail() { return email; }
            public int getAge() { return age; }
            public void setId(String id) { this.id = id; }
            public void setEmail(String email) { this.email = email; }
            public void setAge(int age) { this.age = age; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var navigator = compilation.generated().get("demo.MultiDataTelescope");
      assertNotNull(navigator);
      assertTrue(navigator.contains("id"), () -> "expected 'id' accessor; got: " + navigator);
      assertTrue(navigator.contains("email"), () -> "expected 'email' accessor; got: " + navigator);
      assertTrue(navigator.contains("age"), () -> "expected 'age' accessor; got: " + navigator);
    }

    @Test
    @DisplayName("source without any Lombok annotation: processor stays a no-op (no spurious navigators)")
    void unannotatedSourceProducesNoNavigator() {
      final var compilation = compile(
        new LombokFocusProcessor(),
        source(
          "demo.Plain",
          """
          package demo;
          public class Plain {
            private String name;
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      assertEquals(
        Map.of(),
        compilation.generated(),
        () -> "no Lombok annotation → no navigator; saw " + compilation.generated().keySet()
      );
    }
  }
}
