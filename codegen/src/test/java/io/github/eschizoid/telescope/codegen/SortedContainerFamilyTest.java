package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The interface-family table maps a declared interface to the implementation that keeps its
 * contract. A sorted or concurrent interface names a contract the plain default cannot keep, so
 * rebuilding one into a {@code LinkedHashMap} would satisfy the field's type and drop what the
 * field was declared for.
 *
 * <p>The sorted families divide on what their comparator orders. A map's orders its keys, which a
 * bridge preserves, so the values may change freely. A set's orders its elements, so it survives
 * only while those stay the same type — the case below where it does not is refused rather than
 * reordered.
 *
 * <p>Each case compiles through the full pipeline, because what is checked is an allocation
 * expression and {@code -proc:only} stops before bodies and initializers.
 */
class SortedContainerFamilyTest {

  private static Stream<Arguments> families() {
    return Stream.of(
      Arguments.of("SortedSet", "java.util.SortedSet", "java.util.TreeSet", false),
      Arguments.of("NavigableSet", "java.util.NavigableSet", "java.util.TreeSet", false),
      Arguments.of("SortedMap", "java.util.SortedMap", "java.util.TreeMap", true),
      Arguments.of("NavigableMap", "java.util.NavigableMap", "java.util.TreeMap", true),
      Arguments.of(
        "ConcurrentMap",
        "java.util.concurrent.ConcurrentMap",
        "java.util.concurrent.ConcurrentHashMap",
        true
      )
    );
  }

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  private static JavaFileObject[] pair(final String srcField, final String tgtField) {
    return new JavaFileObject[] {
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
      ProcessorHarness.source("demo.FDst", "package demo; public record FDst(%s) {}".formatted(tgtField)),
    };
  }

  @ParameterizedTest(name = "{0} allocates {2}")
  @MethodSource("families")
  @DisplayName("a field declared with a sorted or concurrent interface allocates that family's default")
  void familyDefaultIsAllocated(
    final String label,
    final String iface,
    final String expectedImpl,
    final boolean isMap
  ) {
    // A map converts its values while keeping its keys; a set keeps its element type, which is what
    // lets its comparator survive.
    final var srcField = isMap ? iface + "<String, demo.SA> items" : "java.util.TreeSet<demo.SA> items";
    final var tgtField = isMap ? iface + "<String, demo.SB> items" : iface + "<demo.SA> items";
    final var compilation = compile(pair(srcField, tgtField));

    assertTrue(compilation.success(), () -> label + " should be bridgeable: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.FSrcBridge");
    assertTrue(
      bridge != null && bridge.contains(expectedImpl),
      () -> label + " should allocate " + expectedImpl + "; saw " + bridge
    );
  }

  @Test
  @DisplayName("a set that converts its elements guards the custom comparator it cannot carry")
  void changingElementsGuardsACustomComparator() {
    // Natural ordering carries over untouched, so refusing every sorted set would reject programs
    // the reflective path accepts. The check is on the comparator and it is made on the value,
    // because a field declared as a plain Set can hold a sorted one.
    final var compilation = compile(pair("java.util.SortedSet<demo.SA> items", "java.util.SortedSet<demo.SB> items"));

    assertTrue(compilation.success(), () -> "natural ordering is fine: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.FSrcBridge");
    assertTrue(
      bridge != null && bridge.contains("__sorted.comparator() != null"),
      () -> "the rebuild must refuse a comparator it cannot reuse; saw " + bridge
    );
  }

  @Test
  @DisplayName("a sorted set keeps its comparator on the route that allocates empty and fills")
  void sortedSetCarriesItsComparator() {
    // Where the two sides are plain generic containers the inline copy runs, and the JDK's own
    // TreeSet(SortedSet) constructor carries the ordering. A raw subtype takes the self-contained
    // helper instead, which allocates empty and fills — so there the comparator has to be passed.
    final var compilation = compile(
      ProcessorHarness.source("demo.SA", "package demo; public record SA(String v) {}"),
      ProcessorHarness.source(
        "demo.Names",
        "package demo; import java.util.TreeSet; public class Names extends" + " TreeSet<demo.SA> {}"
      ),
      ProcessorHarness.source(
        "demo.RSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.RDst.class)
        public record RSrc(java.util.SortedSet<demo.SA> items) {}
        """
      ),
      ProcessorHarness.source("demo.RDst", "package demo; public record RDst(demo.Names items) {}")
    );

    assertTrue(compilation.success(), () -> "identity elements are bridgeable: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.RSrcBridge");
    assertTrue(
      bridge != null && bridge.contains("src.comparator()"),
      () -> "the ordering must be carried where the JDK constructor cannot; saw " + bridge
    );
  }
}
