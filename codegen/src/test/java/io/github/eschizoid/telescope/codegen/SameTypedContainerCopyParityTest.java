package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A component whose source and target declare the same container type, converted by both paths. In
 * every direction a converted value comes back holding a container of its own, so changing it
 * leaves the value it was converted from as it was, and a sorted container keeps the comparator its
 * input was ordered by.
 */
class SameTypedContainerCopyParityTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final String HEAD = "package " + PACKAGE + ";\n";

  /**
   * The one comparator every sorted input is ordered by, so a copy can be asked whether it kept it.
   */
  private static final Comparator<String> REVERSE = Comparator.reverseOrder();

  /**
   * One container shape: the component type both records declare, and a fresh modifiable input
   * holding {@code b} and {@code a}.
   */
  private record Shape(String name, String declared, Supplier<Object> input) {}

  private static final List<Shape> SHAPES = List.of(
    new Shape("List", "java.util.List<String>", () -> new ArrayList<>(List.of("b", "a"))),
    new Shape("Set", "java.util.Set<String>", () -> new LinkedHashSet<>(List.of("b", "a"))),
    new Shape("Map", "java.util.Map<String, String>", () -> new LinkedHashMap<>(Map.of("b", "1", "a", "2"))),
    new Shape("SortedSet", "java.util.SortedSet<String>", () -> {
      final var set = new TreeSet<>(REVERSE);
      set.addAll(List.of("a", "b"));
      return set;
    }),
    new Shape("SortedMap", "java.util.SortedMap<String, String>", () -> {
      final var map = new TreeMap<String, String>(REVERSE);
      map.putAll(Map.of("a", "1", "b", "2"));
      return map;
    })
  );

  /** One path's three directions over a generated pair of records. */
  private interface Path {
    Object forward(Object source) throws ReflectiveOperationException;

    Object backward(Object target) throws ReflectiveOperationException;

    Object patch(Object base, Object partial) throws ReflectiveOperationException;
  }

  private record Pair(Class<?> src, Class<?> tgt, Path generated, Path reflective) {}

  private static Pair compile(final String prefix, final String declared) throws ReflectiveOperationException {
    return compile(prefix, declared, declared);
  }

  private static Pair compile(final String prefix, final String srcDeclared, final String tgtDeclared)
    throws ReflectiveOperationException {
    final var sources = new JavaFileObject[] {
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Src",
        HEAD +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
          prefix +
          "Tgt.class)\npublic record " +
          prefix +
          "Src(" +
          srcDeclared +
          " items) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Tgt",
        HEAD + "public record " + prefix + "Tgt(" + tgtDeclared + " items) {}\n"
      ),
    };
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(plain.success(), plain::errorMessages);
    final var classes = plain.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");

    final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
    assertTrue(processed.success(), processed::errorMessages);
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
    final var forward = bridge.getMethod("forward", src);
    final var backward = bridge.getMethod("backward", tgt);
    final var patch = bridge.getMethod("patch", src, tgt);
    final Path generated = new Path() {
      @Override
      public Object forward(final Object source) throws ReflectiveOperationException {
        return forward.invoke(null, source);
      }

      @Override
      public Object backward(final Object target) throws ReflectiveOperationException {
        return backward.invoke(null, target);
      }

      @Override
      public Object patch(final Object base, final Object partial) throws ReflectiveOperationException {
        return patch.invoke(null, base, partial);
      }
    };

    final Mapper<Object, Object> mapper = Telescope.mapper(cast(src), cast(tgt));
    final Path reflective = new Path() {
      @Override
      public Object forward(final Object source) {
        return mapper.forward(source);
      }

      @Override
      public Object backward(final Object target) {
        return mapper.backward(target);
      }

      @Override
      public Object patch(final Object base, final Object partial) {
        return mapper.patch(base, partial);
      }
    };
    return new Pair(src, tgt, generated, reflective);
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }

  private static Object build(final Class<?> record, final Object items) throws ReflectiveOperationException {
    return record.getConstructors()[0].newInstance(items);
  }

  private static Object itemsOf(final Object record) throws ReflectiveOperationException {
    return record.getClass().getMethod("items").invoke(record);
  }

  /** Adds an entry to a container of any of the shapes, which is the change a copy must absorb. */
  @SuppressWarnings("unchecked")
  private static void change(final Object container) {
    if (container instanceof Map<?, ?> map) ((Map<String, String>) map).put("z", "9");
    else ((Collection<String>) container).add("z");
  }

  private static String rendered(final Object container) {
    return String.valueOf(container);
  }

  private static Comparator<?> comparatorOf(final Object container) {
    if (container instanceof SortedSet<?> set) return set.comparator();
    if (container instanceof SortedMap<?, ?> map) return map.comparator();
    return null;
  }

  /**
   * Asserts {@code out} holds a container of its own: not {@code in}, in the same order and under
   * the same comparator, and one a change to which leaves {@code in} as it was.
   */
  private static void ownsItsContainer(final String where, final Object in, final Object out) {
    final var before = rendered(in);
    assertNotSame(in, out, where + ": the container was handed across rather than copied");
    assertEquals(before, rendered(out), where + ": the copy holds something else");
    assertSame(comparatorOf(in), comparatorOf(out), where + ": the copy lost its comparator");
    change(out);
    assertEquals(before, rendered(in), where + ": changing the copy changed the original");
  }

  @Test
  @DisplayName("forward, backward and patch each give a same-typed container a copy of its own on both paths")
  void everyDirectionCopiesOnBothPaths() throws ReflectiveOperationException {
    var index = 0;
    for (final var shape : SHAPES) {
      final var pair = compile("Stc" + index++, shape.declared());
      for (final var side : List.of(
        Map.entry("generated", pair.generated()),
        Map.entry("reflective", pair.reflective())
      )) {
        final var path = side.getValue();
        final var where = shape.name() + " on the " + side.getKey() + " path, ";

        final var forwardIn = shape.input().get();
        ownsItsContainer(where + "forward", forwardIn, itemsOf(path.forward(build(pair.src(), forwardIn))));

        final var backwardIn = shape.input().get();
        ownsItsContainer(where + "backward", backwardIn, itemsOf(path.backward(build(pair.tgt(), backwardIn))));

        final var patchIn = shape.input().get();
        final var base = build(pair.src(), shape.input().get());
        ownsItsContainer(where + "patch", patchIn, itemsOf(path.patch(base, build(pair.tgt(), patchIn))));
      }
    }
  }

  @Test
  @DisplayName("a container nothing can modify is handed across as itself by both paths")
  void anUnmodifiableInputIsHandedAcrossByBoth() throws ReflectiveOperationException {
    final var pair = compile("Stu", "java.util.List<String>");
    final var items = List.of("b", "a");
    for (final var side : List.of(
      Map.entry("generated", pair.generated()),
      Map.entry("reflective", pair.reflective())
    )) {
      assertSame(items, itemsOf(side.getValue().forward(build(pair.src(), items))), side.getKey() + " forward");
      assertSame(items, itemsOf(side.getValue().backward(build(pair.tgt(), items))), side.getKey() + " backward");
    }
  }

  @Test
  @DisplayName("a same-typed list held as a map value is copied on both paths when the map itself is converted")
  @SuppressWarnings("unchecked")
  void aSameTypedElementOfAConvertedContainerIsCopied() throws ReflectiveOperationException {
    final var pair = compile(
      "Ste",
      "java.util.Map<String, java.util.List<String>>",
      "java.util.LinkedHashMap<String, java.util.List<String>>"
    );
    for (final var side : List.of(
      Map.entry("generated", pair.generated()),
      Map.entry("reflective", pair.reflective())
    )) {
      final var inner = new ArrayList<>(List.of("b", "a"));
      final var input = new LinkedHashMap<String, List<String>>();
      input.put("k", inner);
      final var mapped = (Map<String, List<String>>) itemsOf(side.getValue().forward(build(pair.src(), input)));
      ownsItsContainer("a map value on the " + side.getKey() + " path", inner, mapped.get("k"));
    }
  }
}
