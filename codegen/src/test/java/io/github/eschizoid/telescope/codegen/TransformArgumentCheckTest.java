package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code @Transform} on a property declared with a type variable, which the bridge holds as the
 * variable's erasure, taking a class narrower than that erasure. A class given no type arguments is
 * handed the value after {@code FixedClass.argument} checks its class, in each direction the row
 * emits; any other is refused by the property's name, as before.
 */
class TransformArgumentCheckTest {

  private static final String CHECK = "io.github.eschizoid.telescope.conversion.FixedClass.argument(";

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  /** A {@code BridgeFn<type, type>} named {@code demo.Fn} that hands its argument back. */
  private static JavaFileObject fn(final String type) {
    return ProcessorHarness.source(
      "demo.Fn",
      """
      package demo;
      public final class Fn implements io.github.eschizoid.telescope.conversion.BridgeFn<%1$s, %1$s> {
        @Override public %1$s forward(final %1$s a) { return a; }
        @Override public %1$s backward(final %1$s b) { return b; }
      }
      """.formatted(type)
    );
  }

  /** {@code demo.Src} declaring {@code value} as {@code declared}, bridged to a matching target. */
  private static JavaFileObject[] pair(final String header, final String declared, final String extra) {
    return new JavaFileObject[] {
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        @io.github.eschizoid.telescope.annotations.Bridge(value = demo.Tgt.class, transforms =
          @io.github.eschizoid.telescope.annotations.Transform(field = "value", using = demo.Fn.class%s))
        public record Src%s(%s value) {}
        """.formatted(extra, header, declared)
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt<Y>(Y value) {}"),
    };
  }

  /** The generated {@code method} of {@code demo.SrcBridge}, from its signature to the next. */
  private static String method(final Compilation compilation, final String method) {
    final var bridge = compilation.generated().get("demo.SrcBridge");
    final var start = bridge.indexOf(" " + method + "(final");
    final var end = bridge.indexOf("public static", start + 1);
    return bridge.substring(start, end < 0 ? bridge.length() : end);
  }

  @Test
  @DisplayName("a transform taking a class narrower than the variable checks the value it reads in each direction")
  void aNarrowerClassIsCheckedInEachDirection() {
    final var sources = pair("<X>", "X", "");
    final var compilation = compile(fn("String"), sources[0], sources[1]);
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(
      method(compilation, "forward").contains(
        "((java.lang.String) " + CHECK + "__fs_value, java.lang.String.class, demo.Src.class, \"value\"))"
      ),
      () -> method(compilation, "forward")
    );
    assertTrue(
      method(compilation, "backward").contains(
        "((java.lang.String) " + CHECK + "__bt_value, java.lang.String.class, demo.Tgt.class, \"value\"))"
      ),
      () -> method(compilation, "backward")
    );
    assertTrue(method(compilation, "patch").contains(CHECK), () -> method(compilation, "patch"));
  }

  @Test
  @DisplayName("a forward-only row checks the read it emits and nothing for the backward it does not")
  void aForwardOnlyRowChecksOnlyForward() {
    final var sources = pair("<X>", "X", ", forwardOnly = true");
    final var compilation = compile(fn("String"), sources[0], sources[1]);
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(method(compilation, "forward").contains(CHECK), () -> method(compilation, "forward"));
    assertFalse(method(compilation, "backward").contains(CHECK), () -> method(compilation, "backward"));
  }

  @Test
  @DisplayName("a transform taking a class given type arguments is refused by name, since a check sees only the class")
  void aClassGivenArgumentsIsRefused() {
    final var sources = pair("<X>", "X", "");
    final var compilation = compile(fn("java.util.List<String>"), sources[0], sources[1]);
    assertFalse(compilation.success());
    assertTrue(compilation.hasError("@Transform field=\"value\" does not fit forward"), compilation::errorMessages);
  }

  @Test
  @DisplayName("a property declared as a class, not a variable, is refused a narrower transform by name")
  void aPropertyDeclaredAsAClassIsRefused() {
    final var sources = pair("", "Object", "");
    final var compilation = compile(fn("String"), sources[0], sources[1]);
    assertFalse(compilation.success());
    assertTrue(compilation.hasError("@Transform field=\"value\" does not fit forward"), compilation::errorMessages);
  }

  @Test
  @DisplayName("a transform taking the variable's own erasure is handed the value as it is")
  void theErasureItselfIsNotChecked() {
    final var sources = pair("<X extends Number>", "X", "");
    final var compilation = compile(fn("Number"), sources[0], sources[1]);
    assertTrue(compilation.success(), compilation::errorMessages);
    assertEquals(-1, method(compilation, "forward").indexOf(CHECK), () -> method(compilation, "forward"));
    assertTrue(
      method(compilation, "backward").contains(
        "((java.lang.Number) " + CHECK + "__bt_value, java.lang.Number.class, demo.Tgt.class, \"value\"))"
      ),
      () -> method(compilation, "backward")
    );
  }
}
