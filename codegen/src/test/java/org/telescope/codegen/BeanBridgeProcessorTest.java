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
 * Drives {@link BeanBridgeProcessor} through the shared {@link ProcessorHarness}. Covers the
 * forward (POJO &rarr; record) getter emission and all three backward (record &rarr; POJO)
 * strategies (all-args constructor, static {@code builder()}, no-arg constructor + setters), plus
 * the guards.
 */
class BeanBridgeProcessorTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compile(new BeanBridgeProcessor(), sources);
  }

  @Nested
  @DisplayName("Happy path — backward strategies")
  class HappyPath {

    @Test
    @DisplayName("all-args constructor: forward reads getters, backward calls the constructor in component order")
    void constructorStrategy() {
      final var compilation = compile(
        source(
          "demo.CtorRec",
          """
          package demo;
          import org.telescope.annotations.BeanBridge;
          @BeanBridge(demo.CtorPojo.class)
          public record CtorRec(String id, String email) {}
          """
        ),
        source(
          "demo.CtorPojo",
          """
          package demo;
          public class CtorPojo {
            private final String id;
            private final String email;
            public CtorPojo(String id, String email) { this.id = id; this.email = email; }
            public String getId() { return id; }
            public String getEmail() { return email; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.CtorRecBridge");
      assertNotNull(generated, () -> "CtorRecBridge not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public static final Telescope<demo.CtorPojo, CtorRec> BRIDGE ="), generated);
      assertTrue(generated.contains("Telescope.from(demo.CtorPojo.class).to(CtorRec.class).using("), generated);
      assertTrue(generated.contains("p -> new CtorRec(p.getId(), p.getEmail())"), generated);
      assertTrue(generated.contains("r -> new demo.CtorPojo(r.id(), r.email())"), generated);
    }

    @Test
    @DisplayName("builder(): backward chains builder methods then build()")
    void builderStrategy() {
      final var compilation = compile(
        source(
          "demo.BuilderRec",
          """
          package demo;
          import org.telescope.annotations.BeanBridge;
          @BeanBridge(demo.BuilderPojo.class)
          public record BuilderRec(String id) {}
          """
        ),
        source(
          "demo.BuilderPojo",
          """
          package demo;
          public class BuilderPojo {
            private final String id;
            private BuilderPojo(String id) { this.id = id; }
            public String getId() { return id; }
            public static Builder builder() { return new Builder(); }
            public static final class Builder {
              private String id;
              public Builder id(String id) { this.id = id; return this; }
              public BuilderPojo build() { return new BuilderPojo(id); }
            }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.BuilderRecBridge");
      assertNotNull(generated, () -> "BuilderRecBridge not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("r -> demo.BuilderPojo.builder().id(r.id()).build()"), generated);
    }

    @Test
    @DisplayName("no-arg constructor + setters: backward instantiates then calls setX per component")
    void setterStrategy() {
      final var compilation = compile(
        source(
          "demo.SetterRec",
          """
          package demo;
          import org.telescope.annotations.BeanBridge;
          @BeanBridge(demo.SetterPojo.class)
          public record SetterRec(String id, boolean active) {}
          """
        ),
        source(
          "demo.SetterPojo",
          """
          package demo;
          public class SetterPojo {
            private String id;
            private boolean active;
            public SetterPojo() {}
            public String getId() { return id; }
            public boolean isActive() { return active; }
            public void setId(String id) { this.id = id; }
            public void setActive(boolean active) { this.active = active; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.SetterRecBridge");
      assertNotNull(generated, () -> "SetterRecBridge not generated; saw " + compilation.generated().keySet());

      // Forward uses isX for the boolean component.
      assertTrue(generated.contains("p -> new SetterRec(p.getId(), p.isActive())"), generated);
      assertTrue(generated.contains("new demo.SetterPojo()"), generated);
      assertTrue(generated.contains("p.setId(r.id())"), generated);
      assertTrue(generated.contains("p.setActive(r.active())"), generated);
      assertTrue(generated.contains("return p;"), generated);
    }
  }

  @Nested
  @DisplayName("Rejections — guards raise compile errors")
  class Rejections {

    @Test
    @DisplayName("@BeanBridge on a class (non-record) is an error")
    void nonRecordIsRejected() {
      final var compilation = compile(
        source(
          "demo.NotARecord",
          """
          package demo;
          import org.telescope.annotations.BeanBridge;
          @BeanBridge(demo.NotARecord.class)
          public class NotARecord {}
          """
        )
      );

      assertFalse(compilation.success(), "a class @BeanBridge should fail");
      assertTrue(
        compilation.hasError("@BeanBridge is only supported on records"),
        () -> "expected non-record diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@BeanBridge on a nested record is an error")
    void nestedRecordIsRejected() {
      final var compilation = compile(
        source(
          "demo.Outer",
          """
          package demo;
          import org.telescope.annotations.BeanBridge;
          public class Outer {
            @BeanBridge(demo.Outer.class)
            public record Inner(String a) {}
          }
          """
        )
      );

      assertFalse(compilation.success(), "a nested @BeanBridge record should fail");
      assertTrue(
        compilation.hasError("@BeanBridge is only supported on top-level records"),
        () -> "expected top-level diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("a record component with no matching getter on the POJO is an error")
    void missingGetterIsRejected() {
      final var compilation = compile(
        source(
          "demo.GapRec",
          """
          package demo;
          import org.telescope.annotations.BeanBridge;
          @BeanBridge(demo.GapPojo.class)
          public record GapRec(String id, String missing) {}
          """
        ),
        source(
          "demo.GapPojo",
          """
          package demo;
          public class GapPojo {
            private final String id;
            public GapPojo(String id, String missing) { this.id = id; }
            public String getId() { return id; }
          }
          """
        )
      );

      assertFalse(compilation.success(), "a missing getter should fail");
      assertTrue(
        compilation.hasError("has no getter for record component 'missing'"),
        () -> "expected missing-getter diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("a POJO with no usable construction strategy is an error")
    void noStrategyIsRejected() {
      final var compilation = compile(
        source(
          "demo.NoWayRec",
          """
          package demo;
          import org.telescope.annotations.BeanBridge;
          @BeanBridge(demo.NoWayPojo.class)
          public record NoWayRec(String id) {}
          """
        ),
        source(
          "demo.NoWayPojo",
          """
          package demo;
          public class NoWayPojo {
            private final String id;
            // Two-arg ctor only: no arity-1 all-args ctor, no builder(), no no-arg ctor.
            public NoWayPojo(String id, String extra) { this.id = id; }
            public String getId() { return id; }
          }
          """
        )
      );

      assertFalse(compilation.success(), "a POJO with no construction strategy should fail");
      assertTrue(
        compilation.hasError("no usable construction strategy"),
        () -> "expected no-strategy diagnostic; saw " + compilation.errorMessages()
      );
    }
  }
}
