package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two comparator-constructor shapes the two paths currently decide differently, pinned as they
 * stand so that bringing them into line turns these red.
 *
 * <p>Both constructors erase to {@code (Comparator)}, and neither can order the elements the field
 * fixes. The generated path resolves the parameter against the field's type arguments, sees that,
 * and refuses a source carrying a comparator. The reflective path sees only the declared parameter:
 * a concrete class naming no type variable, or a bare type variable, both pass its check, so it
 * binds the constructor, hands the comparator over, and returns a naturally ordered container with
 * nothing to say an order was dropped.
 */
class SortedComparatorParameterDivergenceTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final String HEAD = "package " + PACKAGE + ";\n";

  /** The generated bridge's forward method, and the source and target classes it was built for. */
  private record Pair(Class<?> src, Class<?> tgt, Method forward) {}

  private static Pair compile(final String prefix, final String container, final String srcField, final String tgtField)
    throws ReflectiveOperationException {
    final var sources = new JavaFileObject[] {
      ProcessorHarness.source(PACKAGE + "." + prefix + "C", HEAD + container),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Src",
        HEAD +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
          prefix +
          "Tgt.class)\npublic record " +
          prefix +
          "Src(" +
          srcField +
          " items) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Tgt",
        HEAD + "public record " + prefix + "Tgt(" + tgtField + " items) {}\n"
      ),
    };
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(plain.success(), plain::errorMessages);
    final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
    assertTrue(processed.success(), processed::errorMessages);

    final var classes = plain.define(MethodHandles.lookup());
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
    assertNotNull(bridge, "the processor should emit a bridge");
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    return new Pair(src, classes.get(PACKAGE + "." + prefix + "Tgt"), bridge.getMethod("forward", src));
  }

  private static void assertGeneratedRefuses(final Pair pair, final Object source) {
    final var thrown = assertThrows(InvocationTargetException.class, () -> pair.forward().invoke(null, source));
    final var cause = assertInstanceOf(IllegalStateException.class, thrown.getCause());
    assertTrue(cause.getMessage().contains("declares no constructor taking a Comparator"), cause::getMessage);
  }

  private static Object reflectiveItems(final Pair pair, final Object source) throws ReflectiveOperationException {
    final var out = Telescope.mapper(cast(pair.src()), cast(pair.tgt())).forward(source);
    return pair.tgt().getMethod("items").invoke(out);
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }

  @Test
  @DisplayName("a set comparator constructor over an unrelated class is refused by one path and dropped by the other")
  void anUnrelatedClassParameterDiverges() throws ReflectiveOperationException {
    final var pair = compile(
      "Sdu",
      "public class SduC<E> extends java.util.TreeSet<E> {\n" +
        "  private static final long serialVersionUID = 1L;\n" +
        "  public SduC() {}\n" +
        "  public SduC(final java.util.Comparator<java.time.LocalDate> ignored) {}\n" +
        "}\n",
      "java.util.SortedSet<java.lang.String>",
      "SduC<java.lang.String>"
    );
    final SortedSet<String> ordered = new TreeSet<>(Comparator.<String>reverseOrder());
    ordered.add("a");
    ordered.add("b");
    final var source = pair.src().getConstructors()[0].newInstance(ordered);

    assertGeneratedRefuses(pair, source);
    final var items = (SortedSet<?>) reflectiveItems(pair, source);
    assertEquals(List.of("a", "b"), List.copyOf(items), "the reflective path reorders the source's [b, a]");
    assertNull(items.comparator(), "and keeps no comparator");
  }

  @Test
  @DisplayName("a map comparator constructor over the value variable is refused by one path and dropped by the other")
  void aValueVariableParameterDiverges() throws ReflectiveOperationException {
    final var pair = compile(
      "Sdv",
      "public class SdvC<K, V> extends java.util.TreeMap<K, V> {\n" +
        "  private static final long serialVersionUID = 1L;\n" +
        "  public SdvC() {}\n" +
        "  public SdvC(final java.util.Comparator<V> ignored) {}\n" +
        "}\n",
      "java.util.SortedMap<java.lang.String, java.lang.Integer>",
      "SdvC<java.lang.String, java.lang.Integer>"
    );
    final SortedMap<String, Integer> ordered = new TreeMap<>(Comparator.<String>reverseOrder());
    ordered.put("a", 1);
    ordered.put("b", 2);
    final var source = pair.src().getConstructors()[0].newInstance(ordered);

    assertGeneratedRefuses(pair, source);
    final var items = (SortedMap<?, ?>) reflectiveItems(pair, source);
    assertEquals(List.of("a", "b"), List.copyOf(items.keySet()), "the reflective path reorders the source's [b, a]");
    assertNull(items.comparator(), "and keeps no comparator");
  }
}
