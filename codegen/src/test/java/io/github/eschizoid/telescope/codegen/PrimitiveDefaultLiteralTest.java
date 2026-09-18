package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A slot nothing fills is written with a default, as source text rather than as a value. A bare
 * {@code 0} is an {@code int} literal and a method invocation permits no narrowing, so the two
 * primitives narrower than {@code int} need a cast to reach their own parameter.
 *
 * <p>Every primitive is covered rather than the two that were broken. They are one expression and
 * one code path, so a fix that special-cased only the failures would satisfy a test covering only
 * the failures while leaving the rule it implements unstated.
 *
 * <p>Both routes that need a default are covered too: a dropped field, and a forward-only transform
 * whose backward is never emitted. They reach the same expression by different callers.
 */
class PrimitiveDefaultLiteralTest {

  private static ProcessorHarness.Compilation dropped(final String primitive) {
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      new JavaFileObject[] {
        ProcessorHarness.source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          @Bridge(value = demo.Tgt.class, drops = { "dropped" })
          public record Src(String keep, %s dropped) {}
          """.formatted(primitive)
        ),
        ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String keep) {}"),
      }
    );
  }

  private static ProcessorHarness.Compilation forwardOnly(final String primitive) {
    return ProcessorHarness.compileFully(
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
          """.formatted(boxed(primitive), boxed(primitive), boxed(primitive), zeroOf(primitive))
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
  }

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

  private static String zeroOf(final String primitive) {
    return switch (primitive) {
      case "boolean" -> "false";
      case "char" -> "'a'";
      case "float" -> "0.0f";
      case "double" -> "0.0";
      default -> "(%s) 0".formatted(primitive);
    };
  }

  @ParameterizedTest(name = "a dropped {0} field")
  @ValueSource(strings = { "byte", "short", "char", "int", "long", "float", "double", "boolean" })
  @DisplayName("a dropped primitive field is filled with a literal its own slot accepts")
  void droppedPrimitiveCompiles(final String primitive) {
    final var compilation = dropped(primitive);

    assertTrue(
      compilation.success(),
      () -> "the emitted default does not reach a " + primitive + " slot: " + compilation.errorMessages()
    );
  }

  @ParameterizedTest(name = "a forward-only {0} field")
  @ValueSource(strings = { "byte", "short", "char", "int", "long", "float", "double", "boolean" })
  @DisplayName("a forward-only transform leaves a backward slot the same literal has to reach")
  void forwardOnlyPrimitiveCompiles(final String primitive) {
    // The second route to the same expression. A backward that is never emitted still leaves the
    // slot it would have written, and the same literal fills it.
    final var compilation = forwardOnly(primitive);

    assertTrue(
      compilation.success(),
      () -> "the emitted default does not reach a " + primitive + " slot: " + compilation.errorMessages()
    );
  }
}
