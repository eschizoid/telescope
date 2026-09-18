package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A slot nothing fills is written with a default, as source text. Every emission of it reaches an
 * argument position, and a method invocation permits no narrowing — so the two primitives narrower
 * than {@code int} need a cast that the others do not.
 *
 * <p>These compile through the full pipeline, and that is load-bearing rather than incidental. The
 * processing-only harness completes declarations and never attributes the emitted expressions, so a
 * literal that cannot reach its slot is invisible to it: all of these pass under {@code compile}
 * and report success. Replacing {@code compileFully} here disarms every row at once.
 *
 * <p>Each row also asserts the literal at its slot rather than only that the compilation succeeded.
 * A bridge that emitted nothing at all would compile too.
 */
class PrimitiveDefaultLiteralTest {

  /**
   * The source text each primitive's default should be. Stated here rather than read from the
   * production table, which would make every row below tautological — and safe to state, because
   * these are the JLS defaults and Java's literal syntax rather than a decision this project makes.
   *
   * <p>Three different obligations sit in one map, and a red row means different things across
   * them. Most are forced: change {@code (byte) 0} or {@code '\0'} and the emitted code stops
   * compiling or stops meaning the same thing. {@code 0L} is not forced by narrowing — a bare
   * {@code 0} widens — but a setter is emitted by name and javac binds the overload, so a target
   * declaring both {@code setX(int)} and {@code setX(long)} takes the wrong one. {@code 0.0d} is
   * neither: it is the same literal as {@code 0.0} and no program can tell them apart, so that row
   * pins a spelling and a red one is cosmetic.
   */
  private static final Map<String, String> EXPECTED = Map.of(
    "byte",
    "(byte) 0",
    "short",
    "(short) 0",
    "char",
    "'\\0'",
    "int",
    "0",
    "long",
    "0L",
    "float",
    "0.0f",
    "double",
    "0.0d",
    "boolean",
    "false"
  );

  private static String boxed(final String primitive) {
    return switch (primitive) {
      case "byte" -> "Byte";
      case "short" -> "Short";
      case "int" -> "Integer";
      case "long" -> "Long";
      case "char" -> "Character";
      case "float" -> "Float";
      case "double" -> "Double";
      default -> "Boolean";
    };
  }

  /**
   * A value other than the primitive's default, so the stub is visibly inert. A forward-only
   * transform emits no backward call at all, so this is never invoked by anything generated —
   * writing the default here would invite a reader to think otherwise.
   */
  private static String nonZeroOf(final String primitive) {
    return switch (primitive) {
      case "boolean" -> "true";
      case "char" -> "'a'";
      case "float" -> "1.0f";
      case "double" -> "1.0";
      case "long" -> "1L";
      default -> "(%s) 1".formatted(primitive);
    };
  }

  @ParameterizedTest(name = "a dropped {0} field")
  @ValueSource(strings = { "byte", "short", "char", "int", "long", "float", "double", "boolean" })
  @DisplayName("a dropped primitive field is rebuilt with a literal its own slot accepts")
  void droppedPrimitiveReachesItsSlot(final String primitive) {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      new JavaFileObject[] {
        ProcessorHarness.source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.Tgt.class, drops = { "gone" })
          public record Src(String keep, %s gone) {}
          """.formatted(primitive)
        ),
        ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String keep) {}"),
      }
    );

    assertTrue(
      compilation.success(),
      () -> "the emitted default does not reach a " + primitive + " slot: " + compilation.errorMessages()
    );
    final var bridge = compilation.generated().get("demo.SrcBridge");
    assertNotNull(bridge, "no bridge was emitted, which every other assertion here would tolerate");
    assertTrue(
      bridge.contains("new demo.Src(__bt_keep, " + EXPECTED.get(primitive) + ")"),
      () -> "the rebuild should pass " + EXPECTED.get(primitive) + " into the dropped slot"
    );
  }

  @ParameterizedTest(name = "a lenient target-only {0} field")
  @ValueSource(strings = { "byte", "short", "char", "int", "long", "float", "double", "boolean" })
  @DisplayName("a target field no source fills is written with a literal its own slot accepts")
  void leniencyFillsTargetOnlyFieldsInTheForwardDirection(final String primitive) {
    // The route that is genuinely a different caller. A dropped field and a forward-only transform
    // are two guards inside one lambda, filling the source's slots on the way back; leniency fills
    // the TARGET's slots on the way forward, which is the other direction and the other record.
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      new JavaFileObject[] {
        ProcessorHarness.source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.Tgt.class, lenient = true)
          public record Src(String keep) {}
          """
        ),
        ProcessorHarness.source(
          "demo.Tgt",
          "package demo; public record Tgt(String keep, %s extra) {}".formatted(primitive)
        ),
      }
    );

    assertTrue(
      compilation.success(),
      () -> "the emitted default does not reach a " + primitive + " slot: " + compilation.errorMessages()
    );
    final var bridge = compilation.generated().get("demo.SrcBridge");
    assertNotNull(bridge, "no bridge was emitted");
    assertTrue(
      bridge.contains("new demo.Tgt(__fs_keep, " + EXPECTED.get(primitive) + ")"),
      () -> "the forward rebuild should pass " + EXPECTED.get(primitive) + " into the unfilled slot"
    );
  }

  @ParameterizedTest(name = "a lenient target-only {0} field written through a setter")
  @ValueSource(strings = { "byte", "short", "char", "int", "long", "float", "double", "boolean" })
  @DisplayName("the same literal has to reach a setter parameter, which is a different emission")
  void leniencyFillsTargetOnlyFieldsThroughSetters(final String primitive) {
    // A record rebuild passes constructor arguments; a bean rebuild calls setters. Both are
    // invocations, so both refuse a narrowing literal, and they are different emitted text. The
    // receiver and the terminator are part of the assertion because the property name alone is a
    // suffix of others -- `unsetExtra(` and `resetExtra(` both contain it.
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      new JavaFileObject[] {
        ProcessorHarness.source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.Tgt.class, lenient = true)
          public record Src(String keep) {}
          """
        ),
        ProcessorHarness.source(
          "demo.Tgt",
          """
          package demo;
          public class Tgt {
            private String keep;
            private %s extra;
            public Tgt() {}
            public String getKeep() { return keep; }
            public void setKeep(final String keep) { this.keep = keep; }
            public %s getExtra() { return extra; }
            public void setExtra(final %s extra) { this.extra = extra; }
          }
          """.formatted(primitive, primitive, primitive)
        ),
      }
    );

    assertTrue(
      compilation.success(),
      () -> "the emitted default does not reach a " + primitive + " setter: " + compilation.errorMessages()
    );
    final var bridge = compilation.generated().get("demo.SrcBridge");
    assertNotNull(bridge, "no bridge was emitted");
    assertTrue(
      bridge.contains("out.setExtra(" + EXPECTED.get(primitive) + ");"),
      () -> "the setter should be handed " + EXPECTED.get(primitive)
    );
  }

  @ParameterizedTest(name = "a forward-only {0} transform")
  @ValueSource(strings = { "byte", "short", "char", "int", "long", "float", "double", "boolean" })
  @DisplayName("a forward-only transform leaves a backward slot the same literal has to reach")
  void forwardOnlyLeavesABackwardSlot(final String primitive) {
    // Not a second caller — this and the dropped case are two guards inside one lambda, filling the
    // same record's slots in the same direction. Kept because the two reach that lambda through
    // different configuration, so it guards the wiring rather than the narrowing.
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      new JavaFileObject[] {
        ProcessorHarness.source(
          "demo.Fn",
          """
          package demo;
          import io.github.eschizoid.telescope.conversion.BridgeFn;
          public final class Fn implements BridgeFn<%s, String> {
            @Override public String forward(final %s v) { return String.valueOf(v); }
            @Override public %s backward(final String s) { return %s; }
          }
          """.formatted(boxed(primitive), boxed(primitive), boxed(primitive), nonZeroOf(primitive))
        ),
        ProcessorHarness.source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Transform;
          @Bridge(value = demo.Tgt.class, transforms = {
            @Transform(field = "v", using = demo.Fn.class, forwardOnly = true)
          })
          public record Src(%s v) {}
          """.formatted(primitive)
        ),
        ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}"),
      }
    );

    assertTrue(
      compilation.success(),
      () -> "the emitted default does not reach a " + primitive + " slot: " + compilation.errorMessages()
    );
    final var bridge = compilation.generated().get("demo.SrcBridge");
    assertNotNull(bridge, "no bridge was emitted");
    assertTrue(
      bridge.contains("new demo.Src(" + EXPECTED.get(primitive) + ")"),
      () -> "the backward rebuild should pass " + EXPECTED.get(primitive) + " where no transform runs"
    );
  }
}
