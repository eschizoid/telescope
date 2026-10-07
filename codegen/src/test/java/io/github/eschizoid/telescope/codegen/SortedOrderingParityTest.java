package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
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
 * Whether a sorted container rebuilt from a source ordered by a comparator keeps that order or is
 * refused, decided once by the shared rules and rendered by both paths.
 *
 * <p>Each row names a container class, the source and target field types, and what both paths owe
 * when the source is ordered in reverse: the reversed contents, or the refusal for a class that
 * cannot be told its order. The rows vary the comparator constructor's parameter, its visibility,
 * the order a subtype declares its type parameters in, and whether the subtype fixes them itself.
 */
class SortedOrderingParityTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final String HEAD = "package " + PACKAGE + ";\n";

  private static final String KEPT_SET = "[b, a]";

  private static final String KEPT_MAP = "{b=2, a=1}";

  private static final String REFUSED = "refused";

  /**
   * One shape. {@code container} is the container class's declaration, with {@code %C} for its
   * name; {@code srcField} and {@code tgtField} are the two field types, with {@code %C} again; and
   * {@code owed} is the rendered contents both paths produce, or {@link #REFUSED}.
   */
  private record Row(String name, String container, String srcField, String tgtField, String owed) {
    boolean map() {
      return srcField.startsWith("java.util.SortedMap") || srcField.startsWith("java.util.Map");
    }
  }

  private static Row set(final String name, final String constructor, final String owed) {
    return new Row(
      name,
      "public class %C<E> extends java.util.TreeSet<E> {\n  public %C() {}\n  " + constructor + "\n}",
      "java.util.SortedSet<String>",
      "%C<String>",
      owed
    );
  }

  private static final List<Row> ROWS = List.of(
    set(
      "over a supertype of the elements",
      "public %C(final java.util.Comparator<? super E> o) { super(o); }",
      KEPT_SET
    ),
    set("over the element variable itself", "public %C(final java.util.Comparator<E> o) { super(o); }", KEPT_SET),
    set("raw", "public %C(final java.util.Comparator o) { super(o); }", KEPT_SET),
    set("over anything", "public %C(final java.util.Comparator<?> o) { super((java.util.Comparator) o); }", KEPT_SET),
    set(
      "over a named supertype",
      "public %C(final java.util.Comparator<Comparable<?>> o) { super((java.util.Comparator) o); }",
      KEPT_SET
    ),
    set(
      "over an unrelated class",
      "public %C(final java.util.Comparator<Integer> o) { super((java.util.Comparator) o); }",
      REFUSED
    ),
    set(
      "over an array type",
      "public %C(final java.util.Comparator<String[]> o) { super((java.util.Comparator) o); }",
      REFUSED
    ),
    set(
      "over the constructor's own variable",
      "public <T> %C(final java.util.Comparator<T> o) { super((java.util.Comparator) o); }",
      REFUSED
    ),
    set("that is not public", "protected %C(final java.util.Comparator<? super E> o) { super(o); }", REFUSED),
    set("that is absent", "", REFUSED),
    new Row(
      "a map whose subtype declares its parameters value first, over the key",
      "public class %C<V, K> extends java.util.TreeMap<K, V> {\n  public %C() {}\n" +
        "  public %C(final java.util.Comparator<? super K> o) { super(o); }\n}",
      "java.util.SortedMap<String, Integer>",
      "%C<Integer, String>",
      KEPT_MAP
    ),
    new Row(
      "a map whose subtype declares its parameters value first, over the value",
      "public class %C<V, K> extends java.util.TreeMap<K, V> {\n  public %C() {}\n" +
        "  public %C(final java.util.Comparator<? super V> o) { super((java.util.Comparator) o); }\n}",
      "java.util.SortedMap<String, Integer>",
      "%C<Integer, String>",
      REFUSED
    ),
    new Row(
      "a map over its entries",
      "public class %C<K, V> extends java.util.TreeMap<K, V> {\n  public %C() {}\n" +
        "  public %C(final java.util.Comparator<? super java.util.Map.Entry<K, V>> o) {}\n}",
      "java.util.SortedMap<String, Integer>",
      "%C<String, Integer>",
      REFUSED
    ),
    new Row(
      "a map with no comparator constructor, read from a plain Map field",
      "public class %C<K, V> extends java.util.TreeMap<K, V> {\n  public %C() {}\n}",
      "java.util.Map<String, Integer>",
      "%C<String, Integer>",
      REFUSED
    ),
    new Row(
      "a subtype fixing its own element type",
      "public class %C extends java.util.TreeSet<String> {\n  public %C() {}\n" +
        "  public %C(final java.util.Comparator<? super String> o) { super(o); }\n}",
      "java.util.SortedSet<String>",
      "%C",
      KEPT_SET
    ),
    new Row(
      "a subtype fixing its own element type, over an unrelated class",
      "public class %C extends java.util.TreeSet<String> {\n  public %C() {}\n" +
        "  public %C(final java.util.Comparator<Integer> o) {}\n}",
      "java.util.SortedSet<String>",
      "%C",
      REFUSED
    ),
    new Row(
      "a set read from a plain Set field",
      "public class %C<E> extends java.util.TreeSet<E> {\n  public %C() {}\n" +
        "  public %C(final java.util.Comparator<? super E> o) { super(o); }\n}",
      "java.util.Set<String>",
      "%C<String>",
      KEPT_SET
    ),
    new Row(
      "a concurrent sorted set subtype",
      "public class %C<E> extends java.util.concurrent.ConcurrentSkipListSet<E> {\n  public %C() {}\n" +
        "  public %C(final java.util.Comparator<? super E> o) { super(o); }\n}",
      "java.util.SortedSet<String>",
      "%C<String>",
      KEPT_SET
    )
  );

  @Test
  @DisplayName("a sorted container keeps its source's comparator or is refused, the same way on both paths")
  void bothPathsDecideTheOrderingAlike() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var row : ROWS) {
      final var prefix = "Sop" + index++;
      final var name = prefix + "C";
      final var sources = new JavaFileObject[] {
        ProcessorHarness.source(
          PACKAGE + "." + name,
          HEAD +
            "@SuppressWarnings({\"unchecked\", \"rawtypes\", \"serial\"})\n" +
            row.container().replace("%C", name) +
            "\n"
        ),
        ProcessorHarness.source(
          PACKAGE + "." + prefix + "Src",
          HEAD +
            "@io.github.eschizoid.telescope.annotations.Bridge(" +
            prefix +
            "Tgt.class)\npublic record " +
            prefix +
            "Src(" +
            row.srcField() +
            " items) {}\n"
        ),
        ProcessorHarness.source(
          PACKAGE + "." + prefix + "Tgt",
          HEAD + "public record " + prefix + "Tgt(" + row.tgtField().replace("%C", name) + " items) {}\n"
        ),
      };
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(plain.success(), () -> row.name() + " should compile: " + plain.errorMessages());
      final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
      assertTrue(processed.success(), () -> row.name() + " should bridge: " + processed.errorMessages());

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
      final var src = classes.get(PACKAGE + "." + prefix + "Src");
      final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
      final var source = src.getConstructors()[0].newInstance(row.map() ? reversedMap() : reversedSet());
      final var items = tgt.getMethod("items");

      final var generated = outcome(() -> items.invoke(bridge.getMethod("forward", src).invoke(null, source)));
      final var reflective = outcome(() -> items.invoke(Telescope.mapper(cast(src), cast(tgt)).forward(source)));
      if (!row.owed().equals(generated)) failures.add(row.name() + ": generated gave " + generated);
      if (!row.owed().equals(reflective)) failures.add(row.name() + ": reflective gave " + reflective);
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }

  private interface Attempt {
    Object get() throws ReflectiveOperationException;
  }

  /**
   * The rebuilt container's contents, or {@link #REFUSED} for the refusal of a class that cannot be
   * told its order, or anything else that was thrown, by name and message.
   */
  private static String outcome(final Attempt attempt) {
    Throwable thrown;
    try {
      return String.valueOf(attempt.get());
    } catch (final InvocationTargetException e) {
      thrown = e.getCause();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      thrown = e;
    }
    final var message = String.valueOf(thrown.getMessage());
    return thrown instanceof IllegalStateException && message.contains("declares no constructor taking a Comparator")
      ? REFUSED
      : thrown.getClass().getSimpleName() + ": " + message;
  }

  private static SortedSet<String> reversedSet() {
    final SortedSet<String> ordered = new TreeSet<>(Comparator.<String>reverseOrder());
    ordered.add("a");
    ordered.add("b");
    return ordered;
  }

  private static SortedMap<String, Integer> reversedMap() {
    final SortedMap<String, Integer> ordered = new TreeMap<>(Comparator.<String>reverseOrder());
    ordered.put("a", 1);
    ordered.put("b", 2);
    return ordered;
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }
}
