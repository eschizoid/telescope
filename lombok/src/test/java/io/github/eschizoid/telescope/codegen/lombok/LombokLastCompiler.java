package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Compiles one source with real javac and this module's test processor path, reordered so Lombok
 * runs after the telescope processors. Whether Lombok has patched a class by the first round
 * depends on where it sits on the processor path, and this is the order in which a first-round read
 * sees the class without the members Lombok adds.
 *
 * <p>The processors are loaded by a class loader over that path alone. javac's own processor loader
 * delegates to the class loader javac was loaded by, which in a test JVM sees the test class path,
 * and with it this module's processor and its service registration — so a processor dropped from
 * the path would still be discovered and run.
 */
final class LombokLastCompiler {

  private LombokLastCompiler() {}

  /**
   * Compiles {@code code} as {@code demo/<simpleName>.java} under {@code dir} and returns the
   * directory generated sources were written to.
   *
   * @param keepTelescopeLombok whether {@code LombokFocusProcessor} stays on the processor path
   */
  static Path compile(final Path dir, final String simpleName, final String code, final boolean keepTelescopeLombok)
    throws IOException {
    return compile(dir, simpleName, code, keepTelescopeLombok, List.of());
  }

  /** {@link #compile(Path, String, String, boolean)} with {@code extraOptions} passed to javac. */
  static Path compile(
    final Path dir,
    final String simpleName,
    final String code,
    final boolean keepTelescopeLombok,
    final List<String> extraOptions
  ) throws IOException {
    final var source = dir.resolve("src/demo/" + simpleName + ".java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, code);
    final var generated = dir.resolve("generated");
    final var classes = dir.resolve("classes");
    Files.createDirectories(generated);
    Files.createDirectories(classes);

    final var compiler = ToolProvider.getSystemJavaCompiler();
    final var diagnostics = new DiagnosticCollector<JavaFileObject>();
    final var processorPath = lombokLast(System.getProperty("telescope.test.processorPath"), keepTelescopeLombok);
    try (
      final var standard = compiler.getStandardFileManager(diagnostics, null, null);
      final var processorLoader = isolatedLoader(processorPath);
      final var files = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
        @Override
        public ClassLoader getClassLoader(final Location location) {
          return location == StandardLocation.ANNOTATION_PROCESSOR_PATH
            ? processorLoader
            : super.getClassLoader(location);
        }
      }
    ) {
      final var options = new ArrayList<String>(extraOptions);
      options.addAll(
        List.of(
          "-parameters",
          "-proc:full",
          "-processorpath",
          processorPath,
          "-classpath",
          System.getProperty("java.class.path"),
          "-s",
          generated.toString(),
          "-d",
          classes.toString()
        )
      );
      final var ok = compiler
        .getTask(null, files, diagnostics, options, null, standard.getJavaFileObjects(source.toFile()))
        .call();
      assertTrue(ok, () -> "compilation failed: " + diagnostics.getDiagnostics());
    }
    return generated;
  }

  /** A class loader over {@code path} that delegates only to the platform class loader. */
  private static URLClassLoader isolatedLoader(final String path) throws IOException {
    final var urls = new ArrayList<URL>();
    for (final var entry : path.split(File.pathSeparator)) urls.add(new File(entry).toURI().toURL());
    return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }

  /** The processor path with the Lombok library moved to the end. */
  private static String lombokLast(final String processorPath, final boolean keepTelescopeLombok) {
    assertNotNull(processorPath, "the build passes the processor path as telescope.test.processorPath");
    final var first = new ArrayList<String>();
    final var last = new ArrayList<String>();
    for (final var entry : processorPath.split(File.pathSeparator)) {
      final var name = new File(entry).getName();
      if (name.startsWith("telescope-lombok")) {
        if (keepTelescopeLombok) first.add(entry);
        continue;
      }
      if (name.startsWith("lombok")) last.add(entry);
      else first.add(entry);
    }
    first.addAll(last);
    return String.join(File.pathSeparator, first);
  }
}
