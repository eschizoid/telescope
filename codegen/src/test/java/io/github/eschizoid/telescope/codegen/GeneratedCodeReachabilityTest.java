package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Generated code lands in the consumer's own package, so everything it names has to be reachable
 * from there: public, and in an exported package. Two things are not, and both are one import away
 * from an emitter that means well — a package-private member of {@code Telescope}, and the {@code
 * internal} packages, which {@code module-info} exports to {@code :core} alone.
 *
 * <p>Neither failure is visible to a processing-only compile, because both land in a method body.
 * Both are invisible to a classpath consumer in one case and to a modular one in the other: a
 * package-private member fails everywhere, while a non-exported package compiles on the classpath
 * and fails only for a consumer that declares a module. These compile through the full pipeline,
 * which catches the first, and assert on the emitted text, which catches the second.
 */
class GeneratedCodeReachabilityTest {

  private static final String INTERNAL_PACKAGE = "io.github.eschizoid.telescope.internal";

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new FocusProcessor()), List.of(), sources);
  }

  private static JavaFileObject record(final String leaf) {
    return ProcessorHarness.source(
      "demo.P",
      "package demo; import io.github.eschizoid.telescope.annotations.Focus;\n" +
        "@Focus public record P(" +
        leaf +
        ") {}"
    );
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
    strings = {
      "java.util.Collection<String> items",
      "Iterable<String> items",
      "java.util.List<String> items",
      "java.util.Set<String> items",
      "java.util.Map<String, String> items",
      "java.util.Optional<String> items",
      "String name",
    }
  )
  @DisplayName("a navigator names nothing the consumer's package cannot reach")
  void navigatorNamesOnlyReachableTypes(final String leaf) {
    final var compilation = compile(record(leaf));

    // The compile is the gate on access: a package-private member of Telescope fails here and
    // nowhere earlier, since the reference sits in a step method's body.
    assertTrue(compilation.success(), () -> leaf + " should compile for a consumer: " + compilation.errorMessages());

    // The text is the gate on exports: a non-exported package compiles for a classpath consumer
    // and fails only for one that declares a module, which no test here can be.
    compilation
      .generated()
      .forEach((name, src) ->
        assertFalse(
          src.contains(INTERNAL_PACKAGE),
          () -> name + " names a package exported to :core alone, so a modular consumer" + " cannot compile it:\n" + src
        )
      );
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "java.util.Deque<String> items", "java.util.Queue<String> items" })
  @DisplayName("a leaf no rebuild can produce is emitted with a warning rather than in silence")
  void unwritableIterableLeafWarns(final String leaf) {
    // The step still reads, so refusing to emit would take away a working capability. What it
    // cannot do is write, and the one moment that is knowable is here rather than at update time.
    final var compilation = compile(record(leaf));

    assertTrue(compilation.success(), () -> leaf + " still reads, so it must still compile");
    final var warned = compilation
      .diagnostics()
      .stream()
      .anyMatch(d -> d.getMessage(null).contains("updating through it throws"));
    assertTrue(warned, () -> "no warning for a leaf that cannot be written: " + compilation.diagnostics());
  }

  @Test
  @DisplayName("a generic record is rejected outright, rather than emitting a step over an erased element")
  void genericRecordIsRejected() {
    // A container component whose element is a type variable has nothing concrete for a step's
    // signature to name. The processor declines the whole record rather than emitting a navigator
    // with a hole in it, and says which component and which type made it decline.
    final var compilation = ProcessorHarness.compileFully(
      List.of(new FocusProcessor()),
      List.of(),
      ProcessorHarness.source(
        "demo.Box",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Focus;
        @Focus public record Box<T>(java.util.List<T> items, String label) {}
        """
      )
    );

    assertFalse(compilation.success(), "an erased element type cannot be navigated");
    assertTrue(
      compilation.hasError("generics with wildcard or self-referential bounds are not supported"),
      () -> "the refusal should name the limitation: " + compilation.errorMessages()
    );
    assertTrue(
      compilation.errorMessages().contains("items"),
      () -> "and the component that triggered it: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a leaf a rebuild can produce is emitted without that warning")
  void writableIterableLeafIsSilent() {
    // The control. Collection and Iterable both admit an ArrayList, so a write through them lands
    // somewhere -- without this, a warning emitted for every iterable leaf would pass the test
    // above.
    Stream.of("java.util.Collection<String> items", "Iterable<String> items")
      .map(leaf -> compile(record(leaf)))
      .forEach(c ->
        assertFalse(
          c
            .diagnostics()
            .stream()
            .anyMatch(d -> d.getMessage(null).contains("updating through it throws")),
          () -> "a writable leaf must not warn: " + c.diagnostics()
        )
      );
  }

  @Test
  @DisplayName("the container leaf with no fixed container reaches its elements through the public factory")
  void iterableLeafUsesThePublicFactory() {
    // List, Set, Map and Optional each have a companion that names their container. Everything
    // else shares one, and it is the branch with no obvious public route to the element traversal.
    final var steps = Stream.of("java.util.Collection<String> items", "Iterable<String> items")
      .map(leaf -> compile(record(leaf)))
      .peek(c -> assertTrue(c.success(), () -> "iterable leaf: " + c.errorMessages()))
      .map(c -> c.generated().get("demo.PItemsStep"))
      .toList();

    steps.forEach(step ->
      assertTrue(
        step != null && step.contains("asIterable(path).each()"),
        () -> "the step must descend through the public factory; saw " + step
      )
    );
  }
}
