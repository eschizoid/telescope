package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
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
    package io.github.eschizoid.telescope.codegen;
    import io.github.eschizoid.telescope.annotations.Bridge;
    @Bridge(HarnessDto.class)
    public record HarnessEntity(String name) {}
    """;

  private static final String DTO = """
    package io.github.eschizoid.telescope.codegen;
    public record HarnessDto(String name) {}
    """;

  /**
   * One definition of each class for the whole class, because a loader holds a name once and
   * defining it twice is a {@link LinkageError}.
   */
  private static final Map<String, Class<?>> CLASSES = compileAndDefine();

  private static Map<String, Class<?>> compileAndDefine() {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.HarnessEntity", PAIR),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.HarnessDto", DTO)
    );
    assertTrue(compilation.success(), () -> "should compile: " + compilation.errorMessages());
    assertTrue(compilation.classes().containsKey("io.github.eschizoid.telescope.codegen.HarnessEntityBridge"), () ->
      compilation.classes().keySet().toString()
    );
    return compilation.define(MethodHandles.lookup());
  }

  @Test
  @DisplayName("a generated bridge can be loaded and invoked, not only read")
  void theGeneratedBridgeRuns() throws Exception {
    final var entityType = CLASSES.get("io.github.eschizoid.telescope.codegen.HarnessEntity");
    final var entity = entityType.getConstructor(String.class).newInstance("Ada");
    final var forward = CLASSES.get("io.github.eschizoid.telescope.codegen.HarnessEntityBridge").getMethod(
      "forward",
      entityType
    );

    assertEquals(
      "HarnessDto[name=Ada]",
      forward.invoke(null, entity).toString(),
      "the generated bridge converts the value it was given"
    );
  }

  @Test
  @DisplayName("the reflective path can read a class the harness compiled, so both paths see one input")
  void theReflectivePathReadsWhatWasCompiled() throws Exception {
    final var entityType = CLASSES.get("io.github.eschizoid.telescope.codegen.HarnessEntity");
    final var entity = entityType.getConstructor(String.class).newInstance("Ada");

    final var converted = Telescope.mapper(
      cast(entityType),
      cast(CLASSES.get("io.github.eschizoid.telescope.codegen.HarnessDto"))
    ).forward(entity);

    assertEquals(
      "HarnessDto[name=Ada]",
      converted.toString(),
      "the runtime accessor substrate builds a reader for a class defined through the test's lookup"
    );
  }

  @Test
  @DisplayName("a proc-only compile produces no classes, so a runner has to ask for the full pipeline")
  void procOnlyProducesNoClasses() {
    final var compilation = ProcessorHarness.compile(
      new BridgeProcessor(),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.HarnessEntity", PAIR),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.HarnessDto", DTO)
    );
    assertTrue(compilation.success(), () -> "should compile: " + compilation.errorMessages());
    assertFalse(compilation.generated().isEmpty(), "it still captures generated source");
    assertTrue(compilation.classes().isEmpty(), "and emits no classes");
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }
}
