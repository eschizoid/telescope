package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a bridge picks the strategy that rebuilds its target: a forced strategy is tried alone and
 * refused in its own words when the target lacks it, and AUTO follows the shared order.
 */
class BridgeWriteStrategyBranchTest {

  private static Compilation compile(final String strategy, final String targetBody) {
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.WriteStrategy;
        @Bridge(value = demo.Tgt.class, writeStrategy = WriteStrategy.%s)
        public record Src(String v) {}
        """.formatted(strategy)
      ),
      ProcessorHarness.source("demo.Tgt", "package demo;\npublic class Tgt {\n" + targetBody + "\n}\n")
    );
  }

  private static final String GETTER = "  private String v;\n  public String getV() { return v; }\n";

  @Test
  @DisplayName("a forced constructor is refused when no public constructor names the fields")
  void forcedConstructorWithoutAMatch() {
    final var compilation = compile("CONSTRUCTOR", GETTER + "  public Tgt(final String other) { this.v = other; }\n");
    assertFalse(compilation.success());
    assertTrue(compilation.hasError("writeStrategy = CONSTRUCTOR"), compilation::errorMessages);
  }

  @Test
  @DisplayName("a forced constructor skips a private one of the right shape")
  void forcedConstructorSkipsAPrivateOne() {
    final var compilation = compile("CONSTRUCTOR", GETTER + "  private Tgt(final String v) { this.v = v; }\n");
    assertFalse(compilation.success());
    assertTrue(compilation.hasError("writeStrategy = CONSTRUCTOR"), compilation::errorMessages);
  }

  @Test
  @DisplayName("a forced builder is refused when there is no builder()")
  void forcedBuilderWithoutABuilder() {
    final var compilation = compile(
      "BUILDER",
      GETTER + "  public Tgt() {}\n  public void setV(final String v) { this.v = v; }\n"
    );
    assertFalse(compilation.success());
    assertTrue(compilation.hasError("writeStrategy = BUILDER"), compilation::errorMessages);
  }

  @Test
  @DisplayName("a forced builder is refused when builder() returns no class")
  void forcedBuilderReturningAPrimitive() {
    final var compilation = compile("BUILDER", GETTER + "  public static int builder() { return 0; }\n");
    assertFalse(compilation.success());
    assertTrue(compilation.hasError("writeStrategy = BUILDER"), compilation::errorMessages);
  }

  @Test
  @DisplayName("forced setters are refused when the only no-arg constructor is private")
  void forcedSettersWithAPrivateNoArgConstructor() {
    final var compilation = compile(
      "SETTERS",
      GETTER + "  private Tgt() {}\n  public void setV(final String v) { this.v = v; }\n"
    );
    assertFalse(compilation.success());
    assertTrue(compilation.hasError("writeStrategy = SETTERS"), compilation::errorMessages);
  }

  @Test
  @DisplayName("forced setters build through them when the target has them")
  void forcedSetters() {
    final var compilation = compile(
      "SETTERS",
      GETTER + "  public Tgt() {}\n  public void setV(final String v) { this.v = v; }\n"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(compilation.generated().get("demo.SrcBridge").contains("out.setV("), () ->
      compilation.generated().get("demo.SrcBridge")
    );
  }
}
