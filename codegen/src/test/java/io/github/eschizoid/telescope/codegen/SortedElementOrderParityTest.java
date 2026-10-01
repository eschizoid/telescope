package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A sorted set target whose elements are converted from the source's. No comparator the source
 * carries can order the converted type, so the target's element has to be {@code Comparable}
 * itself. Both paths refuse the pairing when it is not, the generated one at compile time and the
 * reflective one when the mapper is built, and both convert it when it is.
 */
class SortedElementOrderParityTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final String HEAD = "package " + PACKAGE + ";\n";

  private static final String REFUSAL = "does not implement Comparable";

  private static JavaFileObject[] sources(
    final String prefix,
    final String srcField,
    final String tgtField,
    final boolean comparableDto
  ) {
    return new JavaFileObject[] {
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Leaf",
        HEAD +
          "public record " +
          prefix +
          "Leaf(String v) implements Comparable<" +
          prefix +
          "Leaf> {\n  public int compareTo(final " +
          prefix +
          "Leaf o) { return v.compareTo(o.v()); }\n}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "LeafDto",
        HEAD +
          "public record " +
          prefix +
          "LeafDto(String v)" +
          (comparableDto
            ? " implements Comparable<" +
              prefix +
              "LeafDto> {\n  public int compareTo(final " +
              prefix +
              "LeafDto o) { return v.compareTo(o.v()); }\n}\n"
            : " {}\n")
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Src",
        HEAD +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
          prefix +
          "Tgt.class)\npublic record " +
          prefix +
          "Src(" +
          srcField.replace("%s", prefix) +
          " items) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Tgt",
        HEAD + "public record " + prefix + "Tgt(" + tgtField.replace("%s", prefix) + " items) {}\n"
      ),
    };
  }

  /** What each path does: the target's rendering, or the refusal's message. */
  private static List<String> outcomes(
    final String prefix,
    final String srcField,
    final String tgtField,
    final boolean comparableDto
  ) throws ReflectiveOperationException {
    final var sources = sources(prefix, srcField, tgtField, comparableDto);
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(plain.success(), plain::errorMessages);
    final var classes = plain.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
    final var leaves = new TreeSet<Object>();
    for (final var v : List.of("b", "a")) {
      leaves.add(classes.get(PACKAGE + "." + prefix + "Leaf").getConstructor(String.class).newInstance(v));
    }
    final var source = src.getConstructors()[0].newInstance(leaves);

    final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
    final String generated;
    if (!processed.success()) {
      generated = processed.errorMessages().contains(REFUSAL) ? "refused" : processed.errorMessages();
    } else {
      final var added = new LinkedHashMap<>(processed.classes());
      plain.classes().keySet().forEach(added::remove);
      final var bridge = new ProcessorHarness.Compilation(
        processed.success(),
        processed.diagnostics(),
        processed.generated(),
        processed.resources(),
        added
      )
        .define(MethodHandles.lookup())
        .get(PACKAGE + "." + prefix + "SrcBridge");
      generated = String.valueOf(bridge.getMethod("forward", src).invoke(null, source));
    }

    String reflective;
    try {
      reflective = String.valueOf(Telescope.mapper(cast(src), cast(tgt)).forward(source));
    } catch (final IllegalStateException | IllegalArgumentException e) {
      reflective = e.getMessage().contains(REFUSAL) ? "refused" : e.getMessage();
    }
    return List.of(generated, reflective);
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }

  @Test
  @DisplayName("a sorted set of a converted element that is not Comparable is refused by both paths")
  void anUnorderableElementIsRefusedByBoth() throws ReflectiveOperationException {
    final var shapes = new ArrayList<List<String>>();
    shapes.add(List.of("java.util.SortedSet<%sLeaf>", "java.util.SortedSet<%sLeafDto>"));
    shapes.add(List.of("java.util.Collection<%sLeaf>", "java.util.SortedSet<%sLeafDto>"));
    shapes.add(List.of("java.util.Set<%sLeaf>", "java.util.TreeSet<%sLeafDto>"));
    var index = 0;
    for (final var shape : shapes) {
      assertEquals(
        List.of("refused", "refused"),
        outcomes("Seo" + index++, shape.get(0), shape.get(1), false),
        shape.toString()
      );
    }
  }

  @Test
  @DisplayName("the same pairings convert on both paths when the converted element is Comparable")
  void aComparableElementConvertsOnBoth() throws ReflectiveOperationException {
    final var prefix = "Sec";
    final var expected = prefix + "Tgt[items=[" + prefix + "LeafDto[v=a], " + prefix + "LeafDto[v=b]]]";
    assertEquals(
      List.of(expected, expected),
      outcomes(prefix, "java.util.SortedSet<%sLeaf>", "java.util.SortedSet<%sLeafDto>", true)
    );
  }
}
