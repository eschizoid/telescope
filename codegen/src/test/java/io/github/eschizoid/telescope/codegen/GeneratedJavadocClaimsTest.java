package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Emitted javadoc lives in the adopter's build output, where nothing can revise it. A measurement
 * that moves leaves every previously generated file asserting the old figure, and regenerating only
 * updates the projects that rebuild. So generated prose may describe what a member is, and may not
 * describe what it costs or name what it is faster than.
 *
 * <p>The published comparison is where figures belong, because a figure there has one home and can
 * be corrected in one place.
 */
class GeneratedJavadocClaimsTest {

  /** Words that only appear in generated prose when it is making a claim about speed. */
  private static final List<String> COST_CLAIMS = List.of(
    "MapStruct",
    "hot loop",
    "ns/op",
    "faster",
    "slower",
    "floor",
    "zero-cost",
    "overhead"
  );

  private static JavaFileObject[] plainPair() {
    return new JavaFileObject[] {
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.Dst.class)
        public record Src(String id) {}
        """
      ),
      ProcessorHarness.source("demo.Dst", "package demo; public record Dst(String id) {}"),
    };
  }

  private static JavaFileObject[] sealedPair() {
    // Every permitted subtype carries its own @Bridge; the umbrella's generated bridge dispatches
    // to those. Annotating only the root emits nothing to dispatch to.
    return new JavaFileObject[] {
      ProcessorHarness.source(
        "demo.SA",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.TA.class)
        public record SA(String v) implements demo.Root {}
        """
      ),
      ProcessorHarness.source("demo.TA", "package demo; public record TA(String v) implements demo.TRoot {}"),
      ProcessorHarness.source(
        "demo.Root",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.TRoot.class)
        public sealed interface Root permits demo.SA {}
        """
      ),
      ProcessorHarness.source("demo.TRoot", "package demo; public sealed interface TRoot permits demo.TA {}"),
    };
  }

  private static Stream<String> generatedSources(final JavaFileObject... sources) {
    final var compilation = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
    assertTrue(compilation.success(), () -> "fixture should bridge: " + compilation.errorMessages());
    final var generated = List.copyOf(compilation.generated().values());
    // Without this, a fixture that emits no BRIDGE_FN at all would satisfy every claim assertion
    // below by having nothing to scan, and the emission path it stands for would go unchecked.
    assertTrue(
      generated.stream().anyMatch(src -> src.contains("BRIDGE_FN")),
      () -> "this fixture must reach the constant under test; generated " + compilation.generated().keySet()
    );
    return generated.stream();
  }

  @Test
  @DisplayName("a generated bridge describes what its members are, never what they cost")
  void generatedProseMakesNoCostClaim() {
    // Both emission paths are checked. The plain and sealed bridges emit the same constant, and a
    // claim reintroduced on one of them would otherwise be invisible while the other stayed clean.
    Stream.concat(generatedSources(plainPair()), generatedSources(sealedPair())).forEach(src ->
      COST_CLAIMS.forEach(claim ->
        assertFalse(
          src.contains(claim),
          () -> "generated prose cannot be revised once it ships, so it must not" + " claim '" + claim + "':\n" + src
        )
      )
    );
  }

  @Test
  @DisplayName("the directly-callable constant is still described, so removing the claim did not remove the" + " doc")
  void theConstantIsStillDocumented() {
    // The control. Deleting the javadoc outright would pass the test above and leave the member
    // undocumented, which is the wrong way to satisfy it.
    final var bridge = generatedSources(plainPair())
      .filter(src -> src.contains("BRIDGE_FN"))
      .findFirst()
      .orElseThrow();

    assertTrue(bridge.contains("Directly-callable"), () -> "the constant still needs a description; saw " + bridge);
    assertTrue(bridge.contains("composable"), () -> "and the distinction from BRIDGE; saw " + bridge);
  }
}
