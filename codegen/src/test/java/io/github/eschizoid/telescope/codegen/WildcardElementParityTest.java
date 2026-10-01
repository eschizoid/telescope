package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wildcard elements the cross-path grid does not reach: lower bounds, an unbounded wildcard against
 * one bounded by {@code Object}, and a sorted subtype the adopter wrote, whose allocation has to
 * carry the source's comparator.
 *
 * <p>Each case runs the generated bridge and the reflective mapper over the same input and owes the
 * same answer from both: the rendering and class of the rebuilt container, or a refusal. A refusal
 * from the generated path has to be the processor's own diagnostic, so a javac error inside a
 * generated file cannot pass for one.
 */
class WildcardElementParityTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final String HEAD = "package " + PACKAGE + ";\n";

  /**
   * One pairing. {@code declaration} is an optional container class, {@code reversed} orders the
   * input by a reversing comparator, and {@code owed} is either the rendering and class both paths
   * produce or {@code refused}. {@code %s} is the case's prefix.
   */
  private record Case(
    String name,
    String declaration,
    String srcField,
    String tgtField,
    boolean reversed,
    String owed
  ) {}

  private static final List<Case> CASES = List.of(
    new Case(
      "lower-bounded element into a list class",
      null,
      "java.util.List<? super String>",
      "java.util.ArrayList<? super String>",
      false,
      "%sTgt[items=[a, b]] in java.util.ArrayList"
    ),
    new Case(
      "lower-bounded element into a subtype",
      "public class %sC<E> extends java.util.ArrayList<E> {}",
      "java.util.List<? super String>",
      "%sC<? super String>",
      false,
      "%sTgt[items=[a, b]] in %sC"
    ),
    new Case(
      "unbounded against bounded by Object",
      null,
      "java.util.List<?>",
      "java.util.ArrayList<? extends Object>",
      false,
      "%sTgt[items=[a, b]] in java.util.ArrayList"
    ),
    new Case(
      "different lower bounds",
      null,
      "java.util.List<? super String>",
      "java.util.ArrayList<? super Integer>",
      false,
      "refused"
    ),
    new Case(
      "lower bound against unbounded",
      null,
      "java.util.List<? super String>",
      "java.util.ArrayList<?>",
      false,
      "refused"
    ),
    new Case(
      "sorted subtype with an upper-bounded element",
      "public class %sC<E> extends java.util.TreeSet<E> {\n" +
        "  public %sC() {}\n" +
        "  public %sC(final java.util.Comparator<? super E> order) { super(order); }\n" +
        "}",
      "%sC<? extends String>",
      "java.util.SortedSet<? extends String>",
      true,
      "%sTgt[items=[b, a]] in java.util.TreeSet"
    ),
    new Case(
      "sorted subtype with a lower-bounded element",
      "public class %sC<E> extends java.util.TreeSet<E> {\n" +
        "  public %sC() {}\n" +
        "  public %sC(final java.util.Comparator<? super E> order) { super(order); }\n" +
        "}",
      "%sC<? super String>",
      "java.util.SortedSet<? super String>",
      true,
      "%sTgt[items=[b, a]] in java.util.TreeSet"
    ),
    new Case(
      "into a sorted subtype with an upper-bounded element",
      "public class %sC<E> extends java.util.TreeSet<E> {\n" +
        "  public %sC() {}\n" +
        "  public %sC(final java.util.Comparator<? super E> order) { super(order); }\n" +
        "}",
      "java.util.SortedSet<? extends String>",
      "%sC<? extends String>",
      true,
      "%sTgt[items=[b, a]] in %sC"
    )
  );

  @Test
  @DisplayName("a wildcard element converts the same way on both paths, or is refused by both")
  void aWildcardElementConvertsTheSameWayOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var c : CASES) {
      final var prefix = "We" + index++;
      final var owed = c.owed().replace("%s", prefix).replace(prefix + "C", PACKAGE + "." + prefix + "C");
      final var generated = generated(prefix, c);
      final var reflective = reflective(prefix, c);
      for (final var side : List.of(List.of("generated", generated), List.of("reflective", reflective))) {
        if (!owed.equals(side.get(1))) failures.add(
          c.name() + ": " + side.get(0) + " gave " + side.get(1) + ", owed " + owed
        );
      }
    }
    assertTrue(failures.isEmpty(), () -> failures.size() + " case(s) failed:\n  " + String.join("\n  ", failures));
  }

  private static JavaFileObject[] sources(final String prefix, final Case c) {
    final var files = new ArrayList<JavaFileObject>();
    if (c.declaration() != null) {
      files.add(
        ProcessorHarness.source(PACKAGE + "." + prefix + "C", HEAD + c.declaration().replace("%s", prefix) + "\n")
      );
    }
    files.add(
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Src",
        HEAD +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
          prefix +
          "Tgt.class)\npublic record " +
          prefix +
          "Src(" +
          c.srcField().replace("%s", prefix) +
          " items) {}\n"
      )
    );
    files.add(
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Tgt",
        HEAD + "public record " + prefix + "Tgt(" + c.tgtField().replace("%s", prefix) + " items) {}\n"
      )
    );
    return files.toArray(JavaFileObject[]::new);
  }

  /** The input container: the declared class when the source names it, a JDK class otherwise. */
  @SuppressWarnings("unchecked")
  private static Object input(final Map<String, Class<?>> classes, final String prefix, final Case c)
    throws ReflectiveOperationException {
    final Collection<Object> items;
    if (c.srcField().startsWith("%sC")) {
      final var declared = classes.get(PACKAGE + "." + prefix + "C");
      items = (Collection<Object>) (c.reversed()
        ? declared.getConstructor(Comparator.class).newInstance(Comparator.reverseOrder())
        : declared.getConstructor().newInstance());
    } else if (c.reversed()) {
      final SortedSet<Object> sorted = new TreeSet<>((Comparator<Object>) (Comparator<?>) Comparator.reverseOrder());
      items = sorted;
    } else {
      items = new ArrayList<>();
    }
    items.add("a");
    items.add("b");
    return items;
  }

  private static String generated(final String prefix, final Case c) throws ReflectiveOperationException {
    final var sources = sources(prefix, c);
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(plain.success(), plain::errorMessages);
    final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
    if (!processed.success()) {
      final var message = processed.errorMessages();
      return message.contains("@Bridge") ? "refused" : "javac rejected the generated file: " + message.strip();
    }
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
    final var source = src.getConstructors()[0].newInstance(input(classes, prefix, c));
    try {
      return render(bridge.getMethod("forward", src).invoke(null, source));
    } catch (final InvocationTargetException e) {
      return "threw " + e.getCause();
    }
  }

  private static String reflective(final String prefix, final Case c) throws ReflectiveOperationException {
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources(prefix + "R", c));
    final var classes = plain.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + "." + prefix + "RSrc");
    final var tgt = classes.get(PACKAGE + "." + prefix + "RTgt");
    final var source = src.getConstructors()[0].newInstance(input(classes, prefix + "R", c));
    try {
      final var out = Telescope.mapper(cast(src), cast(tgt)).forward(source);
      return render(out).replace(prefix + "R", prefix);
    } catch (final RuntimeException e) {
      return "refused";
    }
  }

  private static String render(final Object out) throws ReflectiveOperationException {
    final var items = out.getClass().getMethod("items").invoke(out);
    return out + " in " + items.getClass().getName();
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }
}
