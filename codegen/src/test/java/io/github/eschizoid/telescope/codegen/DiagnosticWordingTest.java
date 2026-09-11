package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A diagnostic is read by someone who cannot see the code that produced it, so naming the wrong
 * annotation or describing a symptom instead of a cause sends them somewhere their source does not
 * go. Each test below asserts the message that should appear <em>and</em> that the misleading one
 * does not, because a processor that emitted both would satisfy the first assertion alone.
 */
class DiagnosticWordingTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  @Test
  @DisplayName("an unparseable @Default value is reported against @Default, not @Constant")
  void unparseableDefaultNamesDefault() {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Default;
        @Bridge(value = demo.Dst.class, defaults = @Default(field = "count", value = "not-a-number"))
        public record Src(String name, Integer count) {}
        """
      ),
      ProcessorHarness.source("demo.Dst", "package demo; public record Dst(String name, Integer count) {}")
    );

    assertFalse(compilation.success(), "an unparseable default must not compile");
    assertTrue(
      compilation.hasError("@Default value=\"not-a-number\""),
      () -> "the diagnostic must name the annotation the source carries: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("@Constant"),
      () -> "there is no @Constant in this source: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("an unparseable @Constant value still names @Constant")
  void unparseableConstantStillNamesConstant() {
    // The control for the test above: the shared parser takes the name from its caller, so the
    // other caller has to keep reporting its own.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.CSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Constant;
        @Bridge(value = demo.CDst.class, constants = @Constant(field = "count", value = "not-a-number"))
        public record CSrc(String name) {}
        """
      ),
      ProcessorHarness.source("demo.CDst", "package demo; public record CDst(String name, Integer count) {}")
    );

    assertFalse(compilation.success(), "an unparseable constant must not compile");
    assertTrue(
      compilation.hasError("@Constant value=\"not-a-number\""),
      () -> "the constant path must keep naming @Constant: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("@Default"),
      () -> "there is no @Default in this source: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a plain interface target is reported as an interface, not as a bijection failure")
  void plainInterfaceTargetIsNamedAsSuch() {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.ISrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.IDst.class)
        public record ISrc(String name) {}
        """
      ),
      ProcessorHarness.source("demo.IDst", "package demo; public interface IDst { String name(); }")
    );

    assertFalse(compilation.success(), "an interface target must not compile");
    assertTrue(
      compilation.hasError("is an interface with no permits"),
      () -> "the cause is the target's kind, which the message should say: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("has []"),
      () -> "the empty-component-set wording describes the symptom: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a name already taken by a hand-written type says what to rename")
  void takenBridgeNameSaysWhatToRename() {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.NSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.NDst.class)
        public record NSrc(String name) {}
        """
      ),
      ProcessorHarness.source("demo.NDst", "package demo; public record NDst(String name) {}"),
      // The adopter already owns the name the processor derives for this pair.
      ProcessorHarness.source("demo.NSrcBridge", "package demo; public final class NSrcBridge {}")
    );

    assertFalse(compilation.success(), "the processor cannot write over a hand-written type");
    assertTrue(
      compilation.hasError("that name is already taken"),
      () -> "the message should name the collision and an action: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("Failed to write"),
      () -> "the generic write-failure wording gives the reader nothing to do: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a multi-character value for a char field says so, naming its annotation")
  void multiCharConstantIsRejected() {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.MSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Constant;
        @Bridge(value = demo.MDst.class, constants = @Constant(field = "initial", value = "xy"))
        public record MSrc(String name) {}
        """
      ),
      ProcessorHarness.source("demo.MDst", "package demo; public record MDst(String name, char initial) {}")
    );

    assertFalse(compilation.success(), "two characters do not fit a char");
    assertTrue(
      compilation.hasError("@Constant value=\"xy\" must be a single character"),
      () -> "the char arm reports through the same caller-supplied name: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a taken navigator name reports the same way a taken bridge name does")
  void takenNavigatorNameSaysWhatToRename() {
    // The navigator and the metadata holder are written by different emitters than the bridge, so
    // each carries its own copy of the failure path.
    final var compilation = ProcessorHarness.compileFully(
      List.of(new FocusProcessor()),
      List.of(),
      ProcessorHarness.source(
        "demo.FSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Focus;
        @Focus
        public record FSrc(String name) {}
        """
      ),
      ProcessorHarness.source("demo.FSrcTelescope", "package demo; public final class FSrcTelescope {}"),
      // The metadata holder is written by the utility-class emitter, a third copy of the
      // path.
      ProcessorHarness.source("demo.FSrcFieldOptics", "package demo; public final class FSrcFieldOptics {}")
    );

    assertFalse(compilation.success(), "the processor cannot write over a hand-written navigator");
    assertTrue(
      compilation.hasError("that name is already taken"),
      () -> "every emitter should phrase this the same way: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a lenient bridge writes null into an unmatched collection slot, as the javadoc says")
  void lenientUnmatchedCollectionIsWrittenNull() {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.LSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(value = demo.LDst.class, lenient = true)
        public record LSrc(String name) {}
        """
      ),
      ProcessorHarness.source(
        "demo.LDst",
        "package demo; import java.util.List; public record LDst(String name, List<String>" + " tags, int count) {}"
      )
    );

    assertTrue(compilation.success(), () -> "lenient should compile: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.LSrcBridge");
    assertNotNull(bridge);
    // The unmatched slots take the JLS default for their declared type, which for a collection is
    // null rather than an empty container. Asserting the whole constructor call is what makes this
    // fail if the collection slot changes: every generated bridge contains the word "null" in its
    // guards, so a substring test for it alone would hold no matter what was written here. The
    // primitive slot beside it is the control — it shows the two are decided by type, not by name.
    assertTrue(
      bridge.contains("new demo.LDst(__fs_name, null, 0)"),
      () -> "the collection slot takes null and the primitive takes zero, in one constructor call: " + bridge
    );
  }
}
