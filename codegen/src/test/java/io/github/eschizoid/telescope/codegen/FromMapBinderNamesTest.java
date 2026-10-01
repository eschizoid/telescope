package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code @FromMap} binder is emitted into its target's package and names types there. A type the
 * binder names by its simple name resolves to whatever that name means in the package: a type the
 * user declared there shadows a {@code java.lang} type, and a single-type import shadows a type the
 * user declared there. Either way the binder can bind a type it never meant, or fail to compile.
 *
 * <p>Each cell compiles one user package through the real processor and runs the binder, so the
 * verdict is what the generated code does rather than what its text looks like. The target covers
 * every coercion the binder emits, and is declared with fully qualified names so the user's own
 * source means what it says whatever the package holds.
 */
class FromMapBinderNamesTest {

  /**
   * Every simple name the binder has ever written unqualified: the {@code java.lang} types it
   * names, the types its coercions and its shell import, and the annotation it puts on itself.
   */
  private static final List<String> NAMES = List.of(
    "String",
    "Object",
    "Number",
    "Integer",
    "Boolean",
    "Character",
    "CharSequence",
    "SuppressWarnings",
    "IllegalArgumentException",
    "StringBuilder",
    "List",
    "ArrayList",
    "Set",
    "LinkedHashSet",
    "Map",
    "LinkedHashMap",
    "Optional",
    "Function",
    "ForwardMapper",
    "Instant",
    "UUID",
    "BigDecimal"
  );

  /** The names a component type from another package can carry, colliding with an import. */
  private static final List<String> FOREIGN = List.of(
    "List",
    "Set",
    "Map",
    "Optional",
    "Function",
    "ForwardMapper",
    "Instant"
  );

  private static final String COMPONENTS =
    "java.lang.String s, java.lang.Object o, java.lang.CharSequence cs, int i, java.lang.Integer boxed, " +
    "boolean z, java.lang.Boolean bz, char c, java.lang.Character bc, %1$s.Tone tone, %1$s.Inner inner, " +
    "java.util.List<java.lang.String> list, java.util.Set<java.lang.Integer> set, " +
    "java.util.Map<java.lang.String, java.lang.Integer> map, java.util.Optional<java.lang.String> opt, " +
    "java.time.Instant when, java.util.UUID id, java.math.BigDecimal amount";

  private static Map<String, Object> input() {
    final var input = new LinkedHashMap<String, Object>();
    input.put("s", "text");
    input.put("o", 7L);
    input.put("cs", "chars");
    input.put("i", "3");
    input.put("boxed", 4);
    input.put("z", "true");
    input.put("bz", true);
    input.put("c", "x");
    input.put("bc", 'y');
    input.put("tone", "HIGH");
    input.put("inner", Map.of("v", "in"));
    input.put("list", List.of("a", "b"));
    input.put("set", Set.of(1));
    input.put("map", Map.of("k", 2));
    input.put("opt", "maybe");
    input.put("when", "2026-01-01T00:00:00Z");
    input.put("id", "00000000-0000-0000-0000-000000000001");
    input.put("amount", "1.50");
    return input;
  }

  /** What every binder must hand back for {@link #input()}, read through the target's accessors. */
  private static List<Object> expected() {
    return List.of(
      "text",
      7L,
      "chars",
      3,
      4,
      true,
      true,
      'x',
      'y',
      "HIGH",
      "in",
      List.of("a", "b"),
      Set.of(1),
      Map.of("k", 2),
      Optional.of("maybe"),
      Instant.parse("2026-01-01T00:00:00Z"),
      UUID.fromString("00000000-0000-0000-0000-000000000001"),
      new BigDecimal("1.50")
    );
  }

  private static JavaFileObject source(final String pkg, final String simpleName, final String body) {
    return ProcessorHarness.source(pkg + "." + simpleName, "package " + pkg + ";\n" + body);
  }

  /** The helper types the target's components name, in the target's own package. */
  private static List<JavaFileObject> helpers(final String pkg) {
    return List.of(
      source(pkg, "Tone", "public enum Tone { LOW, HIGH }\n"),
      source(
        pkg,
        "Inner",
        "@io.github.eschizoid.telescope.annotations.FromMap\npublic record Inner(java.lang.String v) {}\n"
      )
    );
  }

  private static JavaFileObject target(final String pkg, final String name, final String extraComponents) {
    return source(
      pkg,
      name,
      "@io.github.eschizoid.telescope.annotations.FromMap(required = {\"s\"})\npublic record " +
        name +
        "(" +
        COMPONENTS.formatted(pkg) +
        extraComponents +
        ") {}\n"
    );
  }

  @Test
  @DisplayName("a target named like any type the binder names is bound as itself")
  void aTargetNamedLikeABinderTypeIsBoundAsItself() {
    final var failures = new ArrayList<String>();
    for (final var name : NAMES) {
      final var pkg = "names.target." + "n" + name.toLowerCase();
      final var sources = new ArrayList<>(helpers(pkg));
      sources.add(target(pkg, name, ""));
      check(failures, "target " + name, pkg, name, sources);
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("a type in the target's package named like any type the binder names leaves the binder unchanged")
  void aSiblingNamedLikeABinderTypeIsIgnored() {
    final var failures = new ArrayList<String>();
    for (final var name : NAMES) {
      final var pkg = "names.sibling." + "n" + name.toLowerCase();
      final var sources = new ArrayList<>(helpers(pkg));
      sources.add(source(pkg, name, "public final class " + name + " {}\n"));
      sources.add(target(pkg, "Target", ""));
      check(failures, "sibling " + name, pkg, "Target", sources);
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("a component type from another package named like a type the binder imports is bound as itself")
  void aForeignComponentNamedLikeAnImportIsBound() {
    final var failures = new ArrayList<String>();
    for (final var name : FOREIGN) {
      final var pkg = "names.foreign." + "n" + name.toLowerCase();
      final var other = pkg + ".other";
      final var sources = new ArrayList<>(helpers(pkg));
      sources.add(source(other, name, "public enum " + name + " { ONE, TWO }\n"));
      sources.add(target(pkg, "Target", ", " + other + "." + name + " foreign"));
      final var input = new LinkedHashMap<>(input());
      input.put("foreign", "TWO");
      check(failures, "foreign " + name, pkg, "Target", sources, input, other + "." + name);
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("a component type in the target's package named like a type the binder imports is bound as itself")
  void aLocalComponentNamedLikeAnImportIsBound() {
    final var failures = new ArrayList<String>();
    for (final var name : FOREIGN) {
      final var pkg = "names.local.n" + name.toLowerCase();
      final var sources = new ArrayList<>(helpers(pkg));
      sources.add(source(pkg, name, "public enum " + name + " { ONE, TWO }\n"));
      sources.add(target(pkg, "Target", ", " + pkg + "." + name + " foreign"));
      final var input = new LinkedHashMap<>(input());
      input.put("foreign", "TWO");
      check(failures, "local " + name, pkg, "Target", sources, input, pkg + "." + name);
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  private static void check(
    final List<String> failures,
    final String cell,
    final String pkg,
    final String targetName,
    final List<JavaFileObject> sources
  ) {
    check(failures, cell, pkg, targetName, sources, input(), null);
  }

  private static void check(
    final List<String> failures,
    final String cell,
    final String pkg,
    final String targetName,
    final List<JavaFileObject> sources,
    final Map<String, Object> input,
    final String foreignEnum
  ) {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new FromMapProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
    if (!compilation.success()) {
      failures.add(
        cell +
          ": the binder does not compile: " +
          compilation
            .errorMessages()
            .lines()
            .filter(l -> l.startsWith("ERROR"))
            .findFirst()
            .orElse("")
      );
      return;
    }
    try {
      final var loader = new InMemoryLoader(compilation.classes());
      final var target = loader.loadClass(pkg + "." + targetName);
      final var built = loader
        .loadClass(pkg + "." + targetName + "FromMap")
        .getMethod("fromMap", Map.class)
        .invoke(null, input);
      if (built.getClass() != target) {
        failures.add(cell + ": the binder built " + built.getClass().getName());
        return;
      }
      final var values = new ArrayList<>();
      for (final var component : target.getRecordComponents()) {
        final var value = component.getAccessor().invoke(built);
        values.add(value instanceof Enum<?> e && !component.getName().equals("foreign") ? e.name() : value);
      }
      final var inner = values.get(10);
      values.set(10, inner.getClass().getRecordComponents()[0].getAccessor().invoke(inner));
      final var want = new ArrayList<>(expected());
      if (foreignEnum != null) want.add(loader.loadClass(foreignEnum).getEnumConstants()[1]);
      if (!want.equals(values)) failures.add(cell + ": bound " + values);
    } catch (final InvocationTargetException e) {
      failures.add(cell + ": the binder threw " + e.getCause());
    } catch (final ReflectiveOperationException e) {
      failures.add(cell + ": could not run the binder: " + e);
    }
  }

  /** One loader per cell, so cells may reuse a class name without colliding. */
  private static final class InMemoryLoader extends ClassLoader {

    private final Map<String, byte[]> classes;

    InMemoryLoader(final Map<String, byte[]> classes) {
      super(FromMapBinderNamesTest.class.getClassLoader());
      this.classes = classes;
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
      final var bytes = classes.get(name);
      if (bytes == null) throw new ClassNotFoundException(name);
      return defineClass(name, bytes, 0, bytes.length);
    }
  }
}
