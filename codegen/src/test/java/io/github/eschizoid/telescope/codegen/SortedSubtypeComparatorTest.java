package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A container whose declared type promises an order is rebuilt with the order the source carried,
 * or the pairing is refused. The comparator is the whole of that promise, and it can only be
 * carried into a container whose own constructor accepts one, because Java does not inherit
 * constructors: a subtype of {@code TreeMap} declaring nothing but a no-argument constructor has
 * nowhere to put it.
 *
 * <p>The failure this guards is quiet. A rebuild without the comparator reorders by the keys' own
 * {@code compareTo}, produces a container of the right type and size, and satisfies every assertion
 * that does not look at iteration order.
 */
class SortedSubtypeComparatorTest {

  private static Compilation compile(final List<JavaFileObject> sources) {
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
  }

  @SafeVarargs
  private static List<JavaFileObject> concat(final List<JavaFileObject>... parts) {
    final var out = new ArrayList<JavaFileObject>();
    for (final var part : parts) out.addAll(part);
    return out;
  }

  private static List<JavaFileObject> elements() {
    return List.of(
      ProcessorHarness.source("demo.SA", "package demo; public record SA(String v) {}"),
      ProcessorHarness.source("demo.SB", "package demo; public record SB(String v) {}")
    );
  }

  private static List<JavaFileObject> pair(final String srcField, final String tgtField) {
    return List.of(
      ProcessorHarness.source(
        "demo.BSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.BDst.class)
        public record BSrc(%s items) {}
        """.formatted(srcField)
      ),
      ProcessorHarness.source("demo.BDst", "package demo; public record BDst(%s items) {}".formatted(tgtField))
    );
  }

  /** A sorted subtype that can be handed a comparator. */
  private static JavaFileObject takesComparator(final String name, final String parent, final String params) {
    return ProcessorHarness.source(
      "demo." + name,
      """
      package demo;
      public class %1$s<%2$s> extends %3$s<%2$s> {
        private static final long serialVersionUID = 1L;
        public %1$s() {}
        public %1$s(final java.util.Comparator<? super K> c) { super(c); }
      }
      """.formatted(name, params, parent)
        .replace("? super K", params.startsWith("K") ? "? super K" : "? super E")
    );
  }

  /** The same shape with no way to receive one. */
  private static JavaFileObject noComparator(final String name, final String parent, final String params) {
    return ProcessorHarness.source(
      "demo." + name,
      """
      package demo;
      public class %1$s<%2$s> extends %3$s<%2$s> {
        private static final long serialVersionUID = 1L;
        public %1$s() {}
      }
      """.formatted(name, params, parent)
    );
  }

  @Test
  @DisplayName("a sorted subtype that accepts a comparator is handed the source's")
  void aSubtypeThatCanTakeTheComparatorGetsIt() {
    final var compilation = compile(
      concat(
        List.of(takesComparator("CmpM", "java.util.TreeMap", "K, V")),
        pair("java.util.SortedMap<String, String>", "demo.CmpM<String, String>")
      )
    );

    assertTrue(compilation.success(), () -> "should bridge: " + compilation.errorMessages());
    assertTrue(
      compilation
        .generated()
        .get("demo.BSrcBridge")
        .contains("new demo.CmpM<java.lang.String, java.lang.String>(src.comparator())"),
      () -> "the comparator should be carried; saw " + compilation.generated().get("demo.BSrcBridge")
    );
  }

  @Test
  @DisplayName("a sorted set subtype that accepts a comparator is handed the source's")
  void aSortedSetSubtypeGetsTheComparator() {
    final var compilation = compile(
      concat(
        List.of(takesComparator("CmpS", "java.util.TreeSet", "E")),
        pair("java.util.SortedSet<String>", "demo.CmpS<String>")
      )
    );

    assertTrue(compilation.success(), () -> "should bridge: " + compilation.errorMessages());
    assertTrue(
      compilation.generated().get("demo.BSrcBridge").contains("new demo.CmpS<java.lang.String>(src.comparator())"),
      () -> "the comparator should be carried; saw " + compilation.generated().get("demo.BSrcBridge")
    );
  }

  @Test
  @DisplayName("a sorted subtype with nowhere to put the comparator refuses a custom one at run time")
  void aSubtypeThatCannotTakeItIsGuarded() {
    // Whether the source carries a custom comparator is not knowable here, and a natural-ordering
    // source rebuilds faithfully, so refusing the pairing outright would refuse conversions that
    // lose nothing. The guard fires only on the one that would.
    final var compilation = compile(
      concat(
        List.of(noComparator("PlainM", "java.util.TreeMap", "K, V")),
        pair("java.util.SortedMap<String, String>", "demo.PlainM<String, String>")
      )
    );

    assertTrue(compilation.success(), () -> "should bridge: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.BSrcBridge");
    assertTrue(
      bridge.contains("comparator() != null") && bridge.contains("throw new IllegalStateException"),
      () -> "a custom comparator should be refused where it would be dropped; saw " + bridge
    );
  }

  @Test
  @DisplayName("an unsorted source has no order to lose, so the same target is accepted")
  void anUnsortedSourceIsNotRefused() {
    // The refusal is about dropping an order the source carried. A plain Map has none, so the
    // target's natural ordering is the only answer available and is not a loss.
    final var compilation = compile(
      concat(
        List.of(noComparator("PlainM", "java.util.TreeMap", "K, V")),
        pair("java.util.Map<String, String>", "demo.PlainM<String, String>")
      )
    );

    assertTrue(compilation.success(), () -> "should bridge: " + compilation.errorMessages());
    assertFalse(
      compilation.generated().get("demo.BSrcBridge").contains("comparator() != null"),
      () -> "an unsorted source needs no guard; saw " + compilation.generated().get("demo.BSrcBridge")
    );
  }

  @Test
  @DisplayName("an interface-declared sorted field still rebuilds through the family default with the" + " comparator")
  void theInterfaceCaseIsUnchanged() {
    final var compilation = compile(
      concat(elements(), pair("java.util.SortedMap<String, demo.SA>", "java.util.SortedMap<String, demo.SB>"))
    );

    assertTrue(compilation.success(), () -> "should bridge: " + compilation.errorMessages());
    assertTrue(
      compilation
        .generated()
        .get("demo.BSrcBridge")
        .contains("new java.util.TreeMap<java.lang.String, demo.SB>(src.comparator())"),
      () -> "saw " + compilation.generated().get("demo.BSrcBridge")
    );
  }

  @Test
  @DisplayName("a sorted subtype declaring no type parameters is still handed the comparator")
  void aTypeParameterlessSubtypeGetsTheComparator() {
    // Taking no type arguments decides how the allocation is written, not whether the type keeps an
    // order, and the two are easy to answer together by accident.
    final var headers = ProcessorHarness.source(
      "demo.Headers",
      """
      package demo;
      public class Headers extends java.util.TreeMap<String, String> {
        private static final long serialVersionUID = 1L;
        public Headers() {}
        public Headers(final java.util.Comparator<? super String> c) { super(c); }
      }
      """
    );
    final var compilation = compile(
      concat(List.of(headers), pair("java.util.SortedMap<String, String>", "demo.Headers"))
    );

    assertTrue(compilation.success(), () -> "should bridge: " + compilation.errorMessages());
    assertTrue(
      compilation.generated().get("demo.BSrcBridge").contains("new demo.Headers(src.comparator())"),
      () -> "the comparator should be carried; saw " + compilation.generated().get("demo.BSrcBridge")
    );
  }
}
