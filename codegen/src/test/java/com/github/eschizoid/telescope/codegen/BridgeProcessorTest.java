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
 * Drives {@link BridgeProcessor} through the shared {@link ProcessorHarness}. Covers every
 * type-pair combination (record&rarr;POJO, record&harr;record, POJO&harr;POJO), the construction
 * strategies, and the guards. The annotated type is the source: {@code @Bridge(Target.class)} on
 * {@code Source} generates {@code SourceBridge.BRIDGE : Telescope<Source, Target>}.
 */
class BridgeProcessorTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compile(new BridgeProcessor(), sources);
  }

  @Nested
  @DisplayName("Happy path — type-pair combinations")
  class HappyPath {

    @Test
    @DisplayName("record -> POJO: forward builds the POJO via its name-matched constructor; backward the record")
    void recordToPojo() {
      final var compilation = compile(
        source(
          "demo.Rec",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Pojo.class)
          public record Rec(String id, String email) {}
          """
        ),
        source(
          "demo.Pojo",
          """
          package demo;
          public class Pojo {
            private final String id;
            private final String email;
            public Pojo(String id, String email) { this.id = id; this.email = email; }
            public String getId() { return id; }
            public String getEmail() { return email; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.RecBridge");
      assertNotNull(generated, () -> "RecBridge not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public static final Telescope<demo.Rec, demo.Pojo> BRIDGE ="), generated);
      assertTrue(generated.contains("Telescope.from(demo.Rec.class).to(demo.Pojo.class).using("), generated);
      assertTrue(generated.contains("s -> new demo.Pojo(s.id(), s.email())"), generated);
      assertTrue(generated.contains("t -> new demo.Rec(t.getId(), t.getEmail())"), generated);
    }

    @Test
    @DisplayName("record <-> record: both sides via canonical constructor")
    void recordToRecord() {
      final var compilation = compile(
        source(
          "demo.A",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.B.class)
          public record A(String id, int score) {}
          """
        ),
        source(
          "demo.B",
          """
          package demo;
          public record B(String id, int score) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.ABridge");
      assertNotNull(generated, () -> "ABridge not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public static final Telescope<demo.A, demo.B> BRIDGE ="), generated);
      assertTrue(generated.contains("s -> new demo.B(s.id(), s.score())"), generated);
      assertTrue(generated.contains("t -> new demo.A(t.id(), t.score())"), generated);
    }

    @Test
    @DisplayName("POJO <-> POJO: both sides via no-arg constructor + setters")
    void pojoToPojo() {
      final var compilation = compile(
        source(
          "demo.PA",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.PB.class)
          public class PA {
            private String id;
            public PA() {}
            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
          }
          """
        ),
        source(
          "demo.PB",
          """
          package demo;
          public class PB {
            private String id;
            public PB() {}
            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
          }
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.PABridge");
      assertNotNull(generated, () -> "PABridge not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public static final Telescope<demo.PA, demo.PB> BRIDGE ="), generated);
      assertTrue(generated.contains("new demo.PB()"), generated);
      assertTrue(generated.contains("out.setId(s.getId())"), generated);
      assertTrue(generated.contains("new demo.PA()"), generated);
      assertTrue(generated.contains("out.setId(t.getId())"), generated);
    }
  }

  @Nested
  @DisplayName("Rejections — guards raise compile errors")
  class Rejections {

    @Test
    @DisplayName("@Bridge on an enum (neither record nor class) is an error")
    void enumIsRejected() {
      final var compilation = compile(
        source(
          "demo.Color",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Color.class)
          public enum Color { RED }
          """
        )
      );

      assertFalse(compilation.success(), "an enum @Bridge should fail");
      assertTrue(
        compilation.hasError("@Bridge is only supported on records and classes"),
        () -> "expected records-and-classes diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("@Bridge on a nested type is an error")
    void nestedIsRejected() {
      final var compilation = compile(
        source(
          "demo.Outer",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.Bridge;
          public class Outer {
            @Bridge(demo.Outer.class)
            public record Inner(String a) {}
          }
          """
        )
      );

      assertFalse(compilation.success(), "a nested @Bridge should fail");
      assertTrue(
        compilation.hasError("@Bridge is only supported on top-level types"),
        () -> "expected top-level diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("mismatched field names (not a bijection) is an error")
    void fieldMismatchIsRejected() {
      final var compilation = compile(
        source(
          "demo.Src",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Dst.class)
          public record Src(String id, String extra) {}
          """
        ),
        source(
          "demo.Dst",
          """
          package demo;
          public record Dst(String id) {}
          """
        )
      );

      assertFalse(compilation.success(), "a non-bijection @Bridge should fail");
      assertTrue(
        compilation.hasError("must expose the same field names"),
        () -> "expected bijection diagnostic; saw " + compilation.errorMessages()
      );
    }

    @Test
    @DisplayName("a target with no usable construction strategy is an error")
    void noStrategyIsRejected() {
      final var compilation = compile(
        source(
          "demo.R",
          """
          package demo;
          import com.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(demo.Immutable.class)
          public record R(String id) {}
          """
        ),
        source(
          "demo.Immutable",
          """
          package demo;
          public class Immutable {
            private final String id;
            // arity-2 ctor only: no arity-1 match, no builder(), no no-arg ctor.
            public Immutable(String id, String extra) { this.id = id; }
            public String getId() { return id; }
          }
          """
        )
      );

      assertFalse(compilation.success(), "a target with no construction strategy should fail");
      assertTrue(
        compilation.hasError("no usable construction strategy"),
        () -> "expected no-strategy diagnostic; saw " + compilation.errorMessages()
      );
    }
  }
}
