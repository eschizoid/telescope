package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code BridgeFn<A, B>} is used in both directions unless the row says otherwise. Forward reads
 * the source field, hands it to {@code A}, and stores {@code B} into the target field; backward
 * makes the same journey in reverse and needs the other two conversions. A pair can satisfy one and
 * not the other, so checking only forward accepts rows whose emitted backward cannot compile.
 *
 * <p>These compile through the full pipeline, because a mismatch that reaches emission surfaces as
 * a javac error inside the generated file — which the processing-only harness never attributes.
 */
class TransformDirectionFitTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  /** A transform whose two type arguments differ, so the two directions are not interchangeable. */
  private static JavaFileObject wideningFn() {
    return ProcessorHarness.source(
      "demo.WideFn",
      """
      package demo;
      import io.github.eschizoid.telescope.conversion.BridgeFn;
      public final class WideFn implements BridgeFn<CharSequence, String> {
        @Override public String forward(final CharSequence c) { return c.toString(); }
        @Override public CharSequence backward(final String s) { return s; }
      }
      """
    );
  }

  private static JavaFileObject[] pair(final String extraTransformArgs) {
    return new JavaFileObject[] {
      wideningFn(),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = {
          @Transform(field = "label", using = demo.WideFn.class%s)
        })
        public record Src(String label) {}
        """.formatted(extraTransformArgs)
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String label) {}"),
    };
  }

  @Test
  @DisplayName("a transform that fits forward but not backward is reported, not emitted")
  void backwardMismatchIsReported() {
    // String reaches CharSequence, and String reaches String, so forward fits and the old check
    // was satisfied. Backward has to store the CharSequence that backward() returns into a String
    // field, which it cannot, and that only shows up once the generated file is attributed.
    final var compilation = compile(pair(""));

    assertFalse(compilation.success(), "a row whose backward cannot compile must be refused");
    assertTrue(
      compilation.hasError("does not fit backward"),
      () -> "the diagnostic should name the failing direction: " + compilation.errorMessages()
    );
    assertTrue(
      compilation.hasError("forwardOnly"),
      () -> "and the remedy, since the row may have been meant one-way: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("cannot be converted to"),
      () -> "the raw error inside the generated file is what this replaces: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("the same transform declared forward-only is accepted, since no backward is emitted")
  void forwardOnlyIsAccepted() {
    // The control, and the reason the check cannot simply demand both directions always fit: a
    // row that emits no backward has no backward to typecheck.
    final var compilation = compile(pair(", forwardOnly = true"));

    assertTrue(compilation.success(), () -> "forward-only rows emit no backward: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("a transform that fits neither direction still names forward, the first thing to fix")
  void forwardMismatchStillNamesForward() {
    // A pair where forward fails too. The message should lead with forward rather than report the
    // second failure, since fixing forward is what the author has to do first.
    final var compilation = compile(
      wideningFn(),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = {
          @Transform(field = "label", using = demo.WideFn.class)
        })
        public record Src(Integer label) {}
        """
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(Integer label) {}")
    );

    assertFalse(compilation.success(), "neither direction fits");
    assertTrue(
      compilation.hasError("does not fit forward"),
      () -> "forward is the half to name first: " + compilation.errorMessages()
    );
  }
}
