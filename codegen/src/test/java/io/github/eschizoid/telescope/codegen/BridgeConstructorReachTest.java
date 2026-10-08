package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bridge builds its target through a constructor only when the package it is emitted into can
 * call that constructor: beside the source for a pair declared on the source, beside the carrier
 * for a carrier-form pair. A package-private constructor in another package is out of reach, and
 * the processor refuses rather than emitting a call javac rejects.
 */
class BridgeConstructorReachTest {

  private static JavaFileObject target(final String constructor) {
    return ProcessorHarness.source(
      "other.Tgt",
      """
      package other;
      public class Tgt {
        private String name;
        %s
        public String getName() { return name; }
        public void setName(final String name) { this.name = name; }
      }
      """.formatted(constructor)
    );
  }

  private static final JavaFileObject SOURCE_ANCHORED = ProcessorHarness.source(
    "demo.Src",
    """
    package demo;
    import io.github.eschizoid.telescope.annotations.Bridge;
    @Bridge(other.Tgt.class)
    public record Src(String name) {}
    """
  );

  private static final JavaFileObject PLAIN_SOURCE = ProcessorHarness.source(
    "demo.Src",
    """
    package demo;
    public record Src(String name) {}
    """
  );

  private static JavaFileObject carrierIn(final String pkg) {
    return ProcessorHarness.source(
      pkg + ".SrcToTgt",
      """
      package %s;
      import io.github.eschizoid.telescope.annotations.Bridge;
      @Bridge(source = demo.Src.class, target = other.Tgt.class)
      public class SrcToTgt {}
      """.formatted(pkg)
    );
  }

  private static ProcessorHarness.Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  @Test
  @DisplayName("a package-private no-arg constructor in another package is refused with a @Bridge diagnostic")
  void aConstructorInAnotherPackageIsRefused() {
    final var compilation = compile(SOURCE_ANCHORED, target("Tgt() {}"));
    assertTrue(
      compilation.hasError("@Bridge: other.Tgt has no usable construction strategy"),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("a package-private no-arg constructor is reached from a carrier in the target's package")
  void aCarrierBesideTheTargetReachesIt() {
    final var compilation = compile(PLAIN_SOURCE, carrierIn("other"), target("Tgt() {}"));
    assertTrue(compilation.success(), compilation::errorMessages);
  }

  @Test
  @DisplayName("a package-private no-arg constructor is refused from a carrier in the source's package")
  void aCarrierBesideTheSourceDoesNotReachIt() {
    final var compilation = compile(PLAIN_SOURCE, carrierIn("demo"), target("Tgt() {}"));
    assertTrue(
      compilation.hasError("@Bridge: other.Tgt has no usable construction strategy"),
      compilation::errorMessages
    );
  }
}
