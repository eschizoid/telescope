package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Constructor parameter names read from a class compiled without {@code -parameters}. Each fixture
 * is compiled here with the debug level under test and loaded from the directory it was written to,
 * so its own bytes are there to read the way an application's are.
 *
 * <p>Inside a native image there are no class bytes, and the suite also runs with the image flag
 * set, so every assertion that names come from the local variable table says what the image answers
 * instead: nothing.
 */
class ParameterNamesTest {

  @TempDir
  Path dir;

  private static final String OUTER =
    "package dbg;\npublic class Outer {\n" +
    "  public class Inner {\n" +
    "    public Inner(final long a, final String b, final double c, final int d) {}\n  }\n" +
    "  public static class Nested {\n" +
    "    public Nested(final double x, final long y, final String z) {}\n  }\n}\n";

  private static final String POINT =
    "package dbg;\npublic class Point {\n  private final String label;\n  private final long x;\n" +
    "  public Point(final long x, final String label) { this.x = x; this.label = label; }\n" +
    "  public String getLabel() { return label; }\n  public long getX() { return x; }\n}\n";

  private static ClassLoader compile(final Path dir, final String debug, final Map<String, String> sources)
    throws IOException {
    final var src = dir.resolve("src");
    final var out = dir.resolve("out");
    final var files = new ArrayList<Path>();
    for (final var entry : sources.entrySet()) {
      final var file = src.resolve(entry.getKey() + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
      files.add(file);
    }
    Files.createDirectories(out);
    final var compiler = ToolProvider.getSystemJavaCompiler();
    try (final var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, null)) {
      final var ok = compiler
        .getTask(
          null,
          fileManager,
          null,
          List.of(debug, "-d", out.toString()),
          null,
          fileManager.getJavaFileObjectsFromPaths(files)
        )
        .call();
      assertTrue(ok, "the fixture should compile");
    }
    return new URLClassLoader(new URL[] { out.toUri().toURL() }, ParameterNamesTest.class.getClassLoader());
  }

  /** What a class compiled with {@code -g} answers: its names, or nothing inside an image. */
  private static String[] expected(final String... names) {
    return NativeImage.IN_IMAGE ? null : names;
  }

  @Test
  @DisplayName("a long or a double takes two slots, and the names still line up with the parameters")
  void wideParametersTakeTwoSlots() throws ReflectiveOperationException, IOException {
    final var nested = Class.forName("dbg.Outer$Nested", false, compile(dir, "-g", Map.of("dbg/Outer", OUTER)));
    final var ctor = nested.getConstructor(double.class, long.class, String.class);

    assertArrayEquals(expected("x", "y", "z"), ParameterNames.of(ctor));
  }

  @Test
  @DisplayName("an inner class's enclosing instance is its constructor's first parameter")
  void theEnclosingInstanceComesFirst() throws ReflectiveOperationException, IOException {
    final var loader = compile(dir, "-g", Map.of("dbg/Outer", OUTER));
    final var outer = Class.forName("dbg.Outer", false, loader);
    final var inner = Class.forName("dbg.Outer$Inner", false, loader);
    final var ctor = inner.getConstructor(outer, long.class, String.class, double.class, int.class);

    assertArrayEquals(expected("this$0", "a", "b", "c", "d"), ParameterNames.of(ctor));
  }

  @Test
  @DisplayName("slots are read past the second half of every wide parameter")
  void slotsSkipTheSecondHalfOfAWideParameter() {
    final var types = new Class<?>[] { long.class, String.class, double.class, int.class };
    final var slots = Map.of(1, "a", 3, "b", 4, "c", 6, "d");

    assertArrayEquals(new String[] { "a", "b", "c", "d" }, ParameterNames.fromSlots(types, slots));
    assertNull(ParameterNames.fromSlots(types, Map.of(1, "a", 3, "b", 4, "c")), "an unnamed slot names nothing");
  }

  @Test
  @DisplayName("a class compiled without a local variable table has no parameter names")
  void noLocalVariableTableNamesNothing() throws ReflectiveOperationException, IOException {
    final var nested = Class.forName("dbg.Outer$Nested", false, compile(dir, "-g:none", Map.of("dbg/Outer", OUTER)));

    assertNull(ParameterNames.of(nested.getConstructor(double.class, long.class, String.class)));
  }

  @Test
  @DisplayName("the automatic writer builds through a constructor whose names only the debug info keeps")
  void theAutomaticWriterMatchesByDebugNames() throws ReflectiveOperationException, IOException {
    final var point = Class.forName("dbg.Point", false, compile(dir, "-g", Map.of("dbg/Point", POINT)));

    if (NativeImage.IN_IMAGE) {
      final var refusal = assertThrows(IllegalStateException.class, () -> Beans.autoWriter(point));
      assertTrue(refusal.getMessage().contains("-parameters or -g"), refusal.getMessage());
      return;
    }
    // The properties are discovered as label then x, the constructor takes x then label, so only a
    // match by name puts each value where it belongs.
    final var built = Beans.autoWriter(point).construct(Beans.propertyNames(point), n -> n.equals("x") ? 7L : "seven");
    assertEquals("seven", point.getMethod("getLabel").invoke(built));
    assertEquals(7L, point.getMethod("getX").invoke(built));
  }

  @Test
  @DisplayName("the automatic writer refuses a constructor whose names nothing keeps, and says how to keep them")
  void theAutomaticWriterRefusesWithoutDebugNames() throws ReflectiveOperationException, IOException {
    final var point = Class.forName("dbg.Point", false, compile(dir, "-g:none", Map.of("dbg/Point", POINT)));

    final var refusal = assertThrows(IllegalStateException.class, () -> Beans.autoWriter(point));
    assertTrue(refusal.getMessage().contains("-parameters or -g"), refusal.getMessage());
  }
}
