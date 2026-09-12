package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The interface-family table decides what to allocate for a field declared as an interface. It
 * knows the three plain families; the sorted and concurrent ones have the same obvious answers and
 * were absent, so a field declared with any of them could not be bridged at all.
 *
 * <p>Each case compiles through the full pipeline, because what is being checked is an allocation
 * expression and {@code -proc:only} stops before bodies and initializers.
 */
class SortedContainerFamilyTest {

  private static Stream<Arguments> families() {
    return Stream.of(
      Arguments.of("SortedSet", "java.util.SortedSet", "java.util.TreeSet"),
      Arguments.of("NavigableSet", "java.util.NavigableSet", "java.util.TreeSet"),
      Arguments.of("SortedMap", "java.util.SortedMap", "java.util.TreeMap"),
      Arguments.of("NavigableMap", "java.util.NavigableMap", "java.util.TreeMap"),
      Arguments.of("ConcurrentMap", "java.util.concurrent.ConcurrentMap", "java.util.concurrent.ConcurrentHashMap")
    );
  }

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  @ParameterizedTest(name = "{0} allocates {2}")
  @MethodSource("families")
  @DisplayName("a field declared with a sorted or concurrent interface allocates that family's default")
  void familyDefaultIsAllocated(final String label, final String iface, final String expectedImpl) {
    final var isMap = iface.contains("Map");
    final var srcField = isMap ? iface + "<String, demo.SA> items" : iface + "<demo.SA> items";
    final var tgtField = isMap ? iface + "<String, demo.SB> items" : iface + "<demo.SB> items";
    final var compilation = compile(
      ProcessorHarness.source("demo.SA", "package demo; public record SA(String v) {}"),
      ProcessorHarness.source("demo.SB", "package demo; public record SB(String v) {}"),
      ProcessorHarness.source(
        "demo.FSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.FDst.class)
        public record FSrc(%s) {}
        """.formatted(srcField)
      ),
      ProcessorHarness.source("demo.FDst", "package demo; public record FDst(%s) {}".formatted(tgtField))
    );

    assertTrue(compilation.success(), () -> label + " should be bridgeable: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.FSrcBridge");
    assertTrue(
      bridge != null && bridge.contains(expectedImpl),
      () -> label + " should allocate " + expectedImpl + "; saw " + bridge
    );
  }
}
