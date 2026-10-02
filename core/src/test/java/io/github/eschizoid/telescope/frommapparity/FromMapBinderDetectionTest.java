package io.github.eschizoid.telescope.frommapparity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.FromMapProcessor;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import io.github.eschizoid.telescope.conversion.FromMapProvider;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether a component type has a generated {@code @FromMap} binder is decided twice: by the
 * processor while it generates the binder of a type that holds it, and by {@code Telescope.fromMap}
 * while it builds a mapper that leaves it to its default. The annotation is source-retained, so
 * neither can read it off a compiled type; both look for the provider the binder registers.
 *
 * <p>The component types here are compiled by the build, through the processor, so a compilation a
 * test runs reads them from their class files, as one module reads another's.
 */
class FromMapBinderDetectionTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.frommapparity";

  /** A record holding one component of {@code componentType}, named {@code name}. */
  private static ProcessorHarness.Compilation compileHolder(final String name, final String componentType) {
    return ProcessorHarness.compileFully(
      List.of(new FromMapProcessor()),
      List.of(),
      ProcessorHarness.source(
        PACKAGE + "." + name,
        "package " +
          PACKAGE +
          ";\nimport io.github.eschizoid.telescope.annotations.FromMap;\n@FromMap\npublic record " +
          name +
          "(" +
          componentType +
          " value) {}\n"
      )
    );
  }

  @Test
  @DisplayName("a binder generated in an earlier compilation registers a provider naming its target")
  void aGeneratedBinderRegistersItsTarget() {
    final var targets = ServiceLoader.load(FromMapProvider.class, Bound.class.getClassLoader())
      .stream()
      .map(p -> p.get().targetType())
      .toList();

    assertTrue(targets.contains(Bound.class), () -> "registered: " + targets);
    assertFalse(targets.contains(Lookalike.class), () -> "registered: " + targets);
  }

  @Test
  @DisplayName("a @FromMap type compiled earlier is accepted by both paths, and both bind it")
  void aTypeFromAnotherCompilationIsAcceptedByBoth() throws ReflectiveOperationException {
    final var compilation = compileHolder("BoundHolder", "Bound");
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var holder = classes.get(PACKAGE + ".BoundHolder");
    final var binder = classes.get(PACKAGE + ".BoundHolderFromMap");

    final var generated = binder
      .getMethod("fromMap", Map.class)
      .invoke(null, Map.of("value", Map.of("city", "Austin")));
    final var value = holder.getMethod("value");
    assertEquals(new Bound("Austin"), value.invoke(generated));

    final var runtime = Telescope.fromMap(holder).forward(Map.of());
    assertNull(value.invoke(runtime), "accepted, and left at its default for an absent key");
  }

  /** The runtime half of the look-alike case: the same holder, compiled by the build. */
  public record LookalikeHolder(Lookalike value) {}

  @Test
  @DisplayName("a type beside a class that only shares its binder's name is refused by both paths")
  void aLookalikeBinderIsRefusedByBoth() {
    final var compilation = ProcessorHarness.compile(
      new FromMapProcessor(),
      ProcessorHarness.source(
        PACKAGE + ".LookalikeRecord",
        "package " +
          PACKAGE +
          ";\nimport io.github.eschizoid.telescope.annotations.FromMap;\n" +
          "@FromMap\npublic record LookalikeRecord(Lookalike value) {}\n"
      )
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(Lookalike.class.getName() + " is a nested object but isn't @FromMap"),
      compilation::errorMessages
    );

    final var refusal = assertThrows(IllegalArgumentException.class, () -> Telescope.fromMap(LookalikeHolder.class));
    assertTrue(
      refusal.getMessage().contains(Lookalike.class.getName() + " has no registered @FromMap binder"),
      refusal::getMessage
    );
  }
}
