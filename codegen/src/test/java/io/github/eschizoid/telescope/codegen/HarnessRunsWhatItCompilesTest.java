package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The harness hands back the classes it compiled, so a test can run generated code instead of
 * reading it.
 *
 * <p>Reading emitted text answers whether a processor wrote what was expected. Running it answers
 * whether the expectation was right, which is the only way to compare a generated path against the
 * reflective one on the same input.
 */
class HarnessRunsWhatItCompilesTest {

  private static final String PAIR = """
    package demo;
    import io.github.eschizoid.telescope.annotations.Bridge;
    @Bridge(Dto.class)
    public record Entity(String name) {}
    """;

  private static final String DTO = """
    package demo;
    public record Dto(String name) {}
    """;

  @Test
  @DisplayName("a generated bridge can be loaded and invoked, not only read")
  void theGeneratedBridgeRuns() throws Exception {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source("demo.Entity", PAIR),
      ProcessorHarness.source("demo.Dto", DTO)
    );
    assertTrue(compilation.success(), () -> "should compile: " + compilation.errorMessages());
    assertTrue(compilation.classes().containsKey("demo.EntityBridge"), () -> compilation.classes().keySet().toString());

    final var loader = compilation.loader();
    final var entity = loader.loadClass("demo.Entity").getConstructor(String.class).newInstance("Ada");
    final var bridge = loader.loadClass("demo.EntityBridge");
    final var forward = bridge.getMethod("forward", loader.loadClass("demo.Entity"));
    final var dto = forward.invoke(null, entity);

    assertEquals("Dto[name=Ada]", dto.toString(), "the generated bridge converts the value it was given");
  }

  @Test
  @DisplayName("a proc-only compile produces no classes, so a runner has to ask for the full pipeline")
  void procOnlyProducesNoClasses() {
    final var compilation = ProcessorHarness.compile(
      new BridgeProcessor(),
      ProcessorHarness.source("demo.Entity", PAIR),
      ProcessorHarness.source("demo.Dto", DTO)
    );
    assertTrue(compilation.success(), () -> "should compile: " + compilation.errorMessages());
    assertFalse(compilation.generated().isEmpty(), "it still captures generated source");
    assertTrue(compilation.classes().isEmpty(), "and emits no classes");
  }
}
