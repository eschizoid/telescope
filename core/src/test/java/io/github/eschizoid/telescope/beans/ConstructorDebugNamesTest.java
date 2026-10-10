package io.github.eschizoid.telescope.beans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A mapper onto a bean built through a constructor whose parameter names a {@code -g} build keeps
 * in the local variable table and nowhere else. On the JVM the mapper reads them from the class's
 * bytes and converts. A native image has no class bytes, and this suite also runs with the image
 * flag set, so there the same mapper refuses and says how to keep the names.
 */
class ConstructorDebugNamesTest {

  @TempDir
  Path dir;

  private static final String SOURCE = "package dbgmap;\npublic record Order(String sku, long qty) {}\n";

  private static final String TARGET =
    "package dbgmap;\npublic class OrderBean {\n  private final long qty;\n  private final String sku;\n" +
    "  public OrderBean(final long qty, final String sku) { this.qty = qty; this.sku = sku; }\n" +
    "  public long getQty() { return qty; }\n  public String getSku() { return sku; }\n}\n";

  @Test
  @DisplayName("a mapper builds a bean through a constructor whose names only the debug info keeps")
  void aMapperBuildsThroughDebugNames() throws IOException, ReflectiveOperationException {
    final var loader = compile();
    final Class<Object> order = cast(Class.forName("dbgmap.Order", true, loader));
    final Class<Object> bean = cast(Class.forName("dbgmap.OrderBean", true, loader));
    final var source = order.getConstructor(String.class, long.class).newInstance("A-1", 3L);

    if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
      final var refusal = assertThrows(RuntimeException.class, () -> Telescope.mapper(order, bean).forward(source));
      assertTrue(String.valueOf(refusal.getMessage()).contains("-parameters or -g"), refusal.getMessage());
      return;
    }
    final var built = Telescope.mapper(order, bean).forward(source);
    assertEquals("A-1", bean.getMethod("getSku").invoke(built));
    assertEquals(3L, bean.getMethod("getQty").invoke(built));
  }

  private ClassLoader compile() throws IOException {
    final var src = dir.resolve("src/dbgmap");
    final var out = dir.resolve("out");
    Files.createDirectories(src);
    Files.createDirectories(out);
    final var files = List.of(
      Files.writeString(src.resolve("Order.java"), SOURCE),
      Files.writeString(src.resolve("OrderBean.java"), TARGET)
    );
    final var compiler = ToolProvider.getSystemJavaCompiler();
    try (final var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, null)) {
      final var ok = compiler
        .getTask(
          null,
          fileManager,
          null,
          List.of("-g", "-d", out.toString()),
          null,
          fileManager.getJavaFileObjectsFromPaths(files)
        )
        .call();
      assertTrue(ok, "the fixture should compile");
    }
    return new URLClassLoader(new URL[] { out.toUri().toURL() }, ConstructorDebugNamesTest.class.getClassLoader());
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }
}
