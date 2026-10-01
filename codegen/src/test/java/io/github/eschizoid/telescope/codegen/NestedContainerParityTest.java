package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A container whose element is itself a container, run through the generated bridge and the
 * reflective mapper. Each case owes the same answer from both: the rendering and class of the
 * rebuilt field, or a refusal, and a refusal from the generated path has to be the processor's own
 * diagnostic.
 *
 * <p>The input is built from the source field's declared type, one element per level, so the
 * rendering is fixed and every level is exercised.
 */
class NestedContainerParityTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final String HEAD = "package " + PACKAGE + ";\n";

  /**
   * One pairing; {@code %s} is the case's prefix, and {@code owed} is a rendering or {@code
   * refused}.
   */
  private record Case(String name, String srcField, String tgtField, String owed) {}

  private static final List<Case> CASES = List.of(
    new Case(
      "list of lists of records",
      "java.util.List<java.util.List<%sLeaf>>",
      "java.util.List<java.util.List<%sLeafDto>>",
      "%sTgt[items=[[%sLeafDto[v=a]]]] in java.util.ArrayList"
    ),
    new Case(
      "inner list rebuilt as a declared class",
      "java.util.List<java.util.List<String>>",
      "java.util.List<java.util.ArrayList<String>>",
      "%sTgt[items=[[a]]] in java.util.ArrayList"
    ),
    new Case(
      "map of lists of records",
      "java.util.Map<String, java.util.List<%sLeaf>>",
      "java.util.Map<String, java.util.List<%sLeafDto>>",
      "%sTgt[items={k=[%sLeafDto[v=a]]}] in java.util.LinkedHashMap"
    ),
    new Case(
      "list of sets of records",
      "java.util.List<java.util.Set<%sLeaf>>",
      "java.util.List<java.util.Set<%sLeafDto>>",
      "%sTgt[items=[[%sLeafDto[v=a]]]] in java.util.ArrayList"
    ),
    new Case(
      "set of lists of records",
      "java.util.Set<java.util.List<%sLeaf>>",
      "java.util.Set<java.util.List<%sLeafDto>>",
      "%sTgt[items=[[%sLeafDto[v=a]]]] in java.util.LinkedHashSet"
    ),
    new Case(
      "list of maps of records",
      "java.util.List<java.util.Map<String, %sLeaf>>",
      "java.util.List<java.util.Map<String, %sLeafDto>>",
      "%sTgt[items=[{k=%sLeafDto[v=a]}]] in java.util.ArrayList"
    ),
    new Case(
      "three levels",
      "java.util.List<java.util.List<java.util.List<%sLeaf>>>",
      "java.util.List<java.util.List<java.util.List<%sLeafDto>>>",
      "%sTgt[items=[[[%sLeafDto[v=a]]]]] in java.util.ArrayList"
    ),
    new Case(
      "nested map keys that differ",
      "java.util.List<java.util.Map<String, %sLeaf>>",
      "java.util.List<java.util.Map<Integer, %sLeafDto>>",
      "refused"
    ),
    new Case(
      "inner elements that cannot pair",
      "java.util.List<java.util.List<String>>",
      "java.util.List<java.util.List<%sLeafDto>>",
      "refused"
    )
  );

  @Test
  @DisplayName("a container of containers converts the same way on both paths, or is refused by both")
  void aNestedContainerConvertsTheSameWayOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var c : CASES) {
      final var prefix = "Nc" + index++;
      final var owed = c.owed().replace("%s", prefix);
      final var generated = generated(prefix, c);
      final var reflective = reflective(prefix + "R", c).replace(prefix + "R", prefix);
      if (!owed.equals(generated)) failures.add(c.name() + ": generated gave " + generated + ", owed " + owed);
      if (!owed.equals(reflective)) failures.add(c.name() + ": reflective gave " + reflective + ", owed " + owed);
    }
    assertTrue(failures.isEmpty(), () -> failures.size() + " case(s) failed:\n  " + String.join("\n  ", failures));
  }

  private static JavaFileObject[] sources(final String prefix, final Case c) {
    return new JavaFileObject[] {
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Leaf",
        HEAD + "public record " + prefix + "Leaf(String v) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "LeafDto",
        HEAD + "public record " + prefix + "LeafDto(String v) {}\n"
      ),
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
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Tgt",
        HEAD + "public record " + prefix + "Tgt(" + c.tgtField().replace("%s", prefix) + " items) {}\n"
      ),
    };
  }

  /** One value of {@code type}, with one element at every level. */
  private static Object valueOf(final Type type, final Map<String, Class<?>> classes)
    throws ReflectiveOperationException {
    if (type == String.class) return "a";
    if (type == Integer.class) return 1;
    if (type instanceof Class<?> cls) return cls.getConstructor(String.class).newInstance("a");
    final var parameterized = (ParameterizedType) type;
    final var raw = (Class<?>) parameterized.getRawType();
    final var arguments = parameterized.getActualTypeArguments();
    if (Map.class.isAssignableFrom(raw)) {
      final var map = new LinkedHashMap<Object, Object>();
      map.put(arguments[0] == String.class ? "k" : 1, valueOf(arguments[1], classes));
      return map;
    }
    final var element = valueOf(arguments[0], classes);
    if (Set.class.isAssignableFrom(raw)) return new LinkedHashSet<>(List.of(element));
    return new ArrayList<>(List.of(element));
  }

  private static Object source(final Map<String, Class<?>> classes, final String prefix)
    throws ReflectiveOperationException {
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    final var type = src.getRecordComponents()[0].getGenericType();
    return src.getConstructors()[0].newInstance(valueOf(type, classes));
  }

  private static String render(final Object out) throws ReflectiveOperationException {
    final var items = out.getClass().getMethod("items").invoke(out);
    return out + " in " + items.getClass().getName();
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
    final var input = source(classes, prefix);
    try {
      final var out = bridge.getMethod("forward", src).invoke(null, input);
      final var back = bridge.getMethod("backward", classes.get(PACKAGE + "." + prefix + "Tgt")).invoke(null, out);
      if (!back.equals(input)) return "backward gave " + back + " from " + input;
      return render(out);
    } catch (final InvocationTargetException e) {
      return "threw " + e.getCause();
    }
  }

  private static String reflective(final String prefix, final Case c) throws ReflectiveOperationException {
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources(prefix, c));
    final var classes = plain.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
    try {
      return render(Telescope.mapper(cast(src), cast(tgt)).forward(source(classes, prefix)));
    } catch (final RuntimeException e) {
      return "refused";
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }
}
