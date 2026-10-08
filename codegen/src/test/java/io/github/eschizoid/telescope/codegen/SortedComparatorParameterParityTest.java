package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * A sorted subtype's comparator constructor, decided the same way by both paths.
 *
 * <p>Every one-argument comparator constructor erases to {@code (Comparator)}, so which one the
 * class declares is read from its parameter, resolved against the arguments the field gave the
 * container. A parameter over a supertype of what the field orders receives the source's
 * comparator. One over an unrelated class, or over a type variable the field bound to something
 * else, cannot, and a source ordered by a comparator is refused on both paths rather than rebuilt
 * in natural order.
 */
class SortedComparatorParameterParityTest {

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

  private static final String REFUSAL = "declares no constructor taking a Comparator";

  /**
   * The remedy both refusals name: a row converting the whole component, which works for any side.
   */
  private static final String REMEDY = "Mapping.to(src, tgt, fwd, bwd)";

  private static void assertGeneratedRefuses(final Pair pair, final Object source) {
    final var thrown = assertThrows(InvocationTargetException.class, () -> pair.forward().invoke(null, source));
    final var cause = assertInstanceOf(IllegalStateException.class, thrown.getCause());
    assertTrue(cause.getMessage().contains(REFUSAL), cause::getMessage);
    assertTrue(cause.getMessage().contains(REMEDY), cause::getMessage);
  }

  private static void assertReflectiveRefuses(final Pair pair, final Object source) {
    final var thrown = assertThrows(IllegalStateException.class, () -> reflectiveItems(pair, source));
    assertTrue(thrown.getMessage().contains(REFUSAL), thrown::getMessage);
    assertTrue(thrown.getMessage().contains(REMEDY), thrown::getMessage);
  }

  private static Object generatedItems(final Pair pair, final Object source) throws ReflectiveOperationException {
    final var out = pair.forward().invoke(null, source);
    return pair.tgt().getMethod("items").invoke(out);
  }

  private static Object reflectiveItems(final Pair pair, final Object source) throws ReflectiveOperationException {
    final var out = Telescope.mapper(cast(pair.src()), cast(pair.tgt())).forward(source);
    return pair.tgt().getMethod("items").invoke(out);
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }

  private static Object reversedSetSource(final Pair pair) throws ReflectiveOperationException {
    final SortedSet<String> ordered = new TreeSet<>(Comparator.<String>reverseOrder());
    ordered.add("a");
    ordered.add("b");
    return pair.src().getConstructors()[0].newInstance(ordered);
  }

  private static Pair setPair(final String prefix, final String parameter) throws ReflectiveOperationException {
    return compile(
      prefix,
      "public class " +
        prefix +
        "C<E> extends java.util.TreeSet<E> {\n" +
        "  private static final long serialVersionUID = 1L;\n" +
        "  public " +
        prefix +
        "C() {}\n" +
        "  public " +
        prefix +
        "C(final " +
        parameter +
        " order) { super((java.util.Comparator) order); }\n" +
        "}\n",
      "java.util.SortedSet<java.lang.String>",
      prefix + "C<java.lang.String>"
    );
  }

  @Test
  @DisplayName("a set comparator constructor over an unrelated class is refused by both paths")
  void anUnrelatedClassParameterIsRefusedByBoth() throws ReflectiveOperationException {
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
    final var source = reversedSetSource(pair);

    assertGeneratedRefuses(pair, source);
    assertReflectiveRefuses(pair, source);
  }

  @Test
  @DisplayName("a map comparator constructor over the value variable is refused by both paths")
  void aValueVariableParameterIsRefusedByBoth() throws ReflectiveOperationException {
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
    assertReflectiveRefuses(pair, source);
  }

  @Test
  @DisplayName("a set comparator constructor over a supertype of the elements keeps the order on both paths")
  void aSupertypeParameterKeepsTheOrderOnBoth() throws ReflectiveOperationException {
    for (final var parameter : List.of(
      "java.util.Comparator<? super E>",
      "java.util.Comparator<E>",
      "java.util.Comparator<java.lang.CharSequence>",
      "java.util.Comparator<java.lang.Object>"
    )) {
      final var pair = setPair("Sds" + Math.abs(parameter.hashCode()), parameter);
      final var source = reversedSetSource(pair);
      for (final var items : List.of(generatedItems(pair, source), reflectiveItems(pair, source))) {
        assertEquals(List.of("b", "a"), List.copyOf((SortedSet<?>) items), parameter);
      }
    }
  }
}
