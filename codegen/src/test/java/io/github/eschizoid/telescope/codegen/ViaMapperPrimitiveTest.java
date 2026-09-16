package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A user-supplied bridge is called through a null gate, because it is user code with no guaranteed
 * null tolerance. A primitive field has no null to guard against, and {@code int == null} is not
 * legal Java, so the gate has to be skipped there rather than emitted and hoped over.
 *
 * <p>These compile through the full pipeline. The gate lands in a method body, which the
 * processing-only harness does not attribute, so an assertion made there would hold no matter what
 * was emitted — which is why every existing {@code @ViaMapper} fixture, all of them
 * reference-typed, could not have caught this.
 */
class ViaMapperPrimitiveTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  private static JavaFileObject bridge(final String fromType, final String toType) {
    return ProcessorHarness.source(
      "demo.QtyBridge",
      """
      package demo;
      public final class QtyBridge {
        public static %s forward(final %s s) { return %s; }
        public static %s backward(final %s t) { return %s; }
      }
      """.formatted(
          toType,
          fromType,
          "int".equals(toType) ? "Integer.parseInt(s)" : "String.valueOf(s)",
          fromType,
          toType,
          "int".equals(fromType) ? "Integer.parseInt(t)" : "String.valueOf(t)"
        )
    );
  }

  private static JavaFileObject[] pair(final String srcQty, final String tgtQty) {
    return new JavaFileObject[] {
      ProcessorHarness.source(
        "demo.PSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.ViaMapper;
        @Bridge(value = demo.PDst.class, viaMappers = {
          @ViaMapper(field = "qty", using = demo.QtyBridge.class)
        })
        public record PSrc(String id, %s qty) {}
        """.formatted(srcQty)
      ),
      ProcessorHarness.source("demo.PDst", "package demo; public record PDst(String id, %s qty) {}".formatted(tgtQty)),
      bridge(srcQty, tgtQty),
    };
  }

  @Test
  @DisplayName("a primitive source field is handed to the user bridge without a null gate")
  void primitiveSourceCompiles() {
    final var compilation = compile(pair("int", "String"));

    assertTrue(compilation.success(), () -> "an int cannot be null-checked: " + compilation.errorMessages());
    final var generated = compilation.generated().get("demo.PSrcBridge");
    assertFalse(
      generated.contains("__fs_qty == null"),
      () -> "a primitive local has no null to test for; saw " + generated
    );
  }

  @Test
  @DisplayName("a primitive target field is handed to the user bridge without a null gate on backward")
  void primitiveTargetCompiles() {
    // The mirror. The forward direction reads a reference here, so only the backward local is
    // primitive — a fix applied to one direction alone would leave this one failing.
    final var compilation = compile(pair("String", "int"));

    assertTrue(compilation.success(), () -> "an int cannot be null-checked: " + compilation.errorMessages());
    final var generated = compilation.generated().get("demo.PSrcBridge");
    assertFalse(generated.contains("__bt_qty == null"), () -> "the backward local is primitive; saw " + generated);
  }

  @Test
  @DisplayName("a reference field keeps its gate, since a user bridge need not tolerate null")
  void referenceFieldKeepsTheGate() {
    // The control. Without it a fix that dropped the gate everywhere would pass the two above.
    final var compilation = compile(pair("String", "String"));

    assertTrue(compilation.success(), () -> "the reference pair still bridges: " + compilation.errorMessages());
    final var generated = compilation.generated().get("demo.PSrcBridge");
    assertTrue(generated.contains("__fs_qty == null"), () -> "a reference local keeps its gate; saw " + generated);
  }
}
