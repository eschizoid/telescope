package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A named module finds a service provider only through a {@code provides} directive, never through
 * {@code META-INF/services}, so a binder compiled into one is invisible to a runtime {@code
 * fromMap} unless its {@code module-info} declares it. The processor sees the module it compiles
 * and says which line to add.
 */
class FromMapModulePathTest {

  private static final String LINE =
    "provides io.github.eschizoid.telescope.conversion.FromMapProvider with demo.InnerFromMap.Provider, " +
    "demo.OuterFromMap.Provider;";

  /**
   * The modular entries of this test's class path, which is where telescope's modules come from.
   */
  private static String modulePath() {
    return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
      .filter(FromMapModulePathTest::isModular)
      .collect(Collectors.joining(File.pathSeparator));
  }

  private static boolean isModular(final String entry) {
    final var path = Path.of(entry);
    if (Files.isDirectory(path)) return Files.exists(path.resolve("module-info.class"));
    if (!entry.endsWith(".jar") || !Files.exists(path)) return false;
    try (final var jar = new JarFile(path.toFile())) {
      return jar.getEntry("module-info.class") != null;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ProcessorHarness.Compilation compileModule(final String directives) {
    return ProcessorHarness.compileFully(
      List.of(new FromMapProcessor()),
      List.of("--module-path", modulePath()),
      ProcessorHarness.source(
        "module-info",
        "module demo {\n  requires io.github.eschizoid.telescope;\n  exports demo;\n  opens demo;\n" +
          directives +
          "}\n"
      ),
      ProcessorHarness.source(
        "demo.Inner",
        "package demo;\n@io.github.eschizoid.telescope.annotations.FromMap\npublic record Inner(String city) {}\n"
      ),
      ProcessorHarness.source(
        "demo.Outer",
        "package demo;\n@io.github.eschizoid.telescope.annotations.FromMap\npublic record Outer(demo.Inner inner) {}\n"
      )
    );
  }

  private static List<String> warnings(final ProcessorHarness.Compilation compilation) {
    return compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.MANDATORY_WARNING || d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .filter(m -> m.startsWith("@FromMap"))
      .toList();
  }

  @Test
  @DisplayName("a named module that does not provide its binders is told the provides line to add")
  void aModuleWithoutProvidesIsToldTheLine() {
    final var compilation = compileModule("");

    assertTrue(compilation.success(), compilation::errorMessages);
    final var warnings = warnings(compilation);
    assertTrue(
      warnings.stream().anyMatch(w -> w.contains("module demo") && w.endsWith(LINE)),
      () -> "warnings: " + warnings
    );
  }

  @Test
  @DisplayName("the line, once added, compiles and silences the warning")
  void aModuleThatProvidesItsBindersIsNotWarned() {
    final var compilation = compileModule("  " + LINE + "\n");

    assertTrue(compilation.success(), compilation::errorMessages);
    assertFalse(compilation.classes().isEmpty(), "the module compiled");
    assertTrue(warnings(compilation).isEmpty(), () -> "warnings: " + warnings(compilation));
  }

  @Test
  @DisplayName("a module providing only some of its binders is told the whole line, which replaces its own")
  void aModuleProvidingSomeIsToldTheWholeLine() {
    final var compilation = compileModule(
      "  provides io.github.eschizoid.telescope.conversion.FromMapProvider with demo.InnerFromMap.Provider;\n"
    );

    assertTrue(compilation.success(), compilation::errorMessages);
    final var warnings = warnings(compilation);
    assertTrue(warnings.stream().anyMatch(w -> w.endsWith(LINE)), () -> "warnings: " + warnings);
  }

  /**
   * Runs {@code Telescope.fromMap(demo.Outer)} inside a module layer built from the compiled module
   * and telescope's own modules, so the lookup sees what an application on the module path sees.
   * Answers with the refusal message, or null when the mapper was built.
   */
  private static String runtimeVerdict(final ProcessorHarness.Compilation compilation) throws Exception {
    final var dir = Files.createTempDirectory("frommap-module");
    for (final var entry : compilation.classes().entrySet()) {
      final var file = dir.resolve(entry.getKey().replace('.', '/') + ".class");
      Files.createDirectories(file.getParent());
      Files.write(file, entry.getValue());
    }
    final var entries = new ArrayList<Path>();
    entries.add(dir);
    for (final var entry : modulePath().split(File.pathSeparator)) entries.add(Path.of(entry));
    final var finder = ModuleFinder.of(entries.toArray(Path[]::new));
    final var configuration = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), Set.of("demo"));
    final var layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
    final var loader = layer.findLoader("demo");
    final var outer = loader.loadClass("demo.Outer");
    final var steps = loader.loadClass("io.github.eschizoid.telescope.mapping.MapExtractStep");
    final var fromMap = loader
      .loadClass("io.github.eschizoid.telescope.Telescope")
      .getMethod("fromMap", Class.class, steps.arrayType());
    try {
      fromMap.invoke(null, outer, Array.newInstance(steps, 0));
      return null;
    } catch (final InvocationTargetException e) {
      return e.getCause().getMessage();
    }
  }

  @Test
  @DisplayName("on the module path, a binder the module does not provide is refused with the line that registers it")
  void onTheModulePathAnUndeclaredBinderIsRefused() throws Exception {
    final var refusal = runtimeVerdict(compileModule(""));

    assertTrue(
      refusal != null && refusal.contains("demo.Inner has no registered @FromMap binder"),
      () -> "got " + refusal
    );
    assertTrue(
      refusal.contains(
        "provides io.github.eschizoid.telescope.conversion.FromMapProvider with demo.InnerFromMap.Provider;"
      ),
      refusal
    );
  }

  @Test
  @DisplayName("on the module path, a binder the module provides is found")
  void onTheModulePathADeclaredBinderIsFound() throws Exception {
    final var refusal = runtimeVerdict(compileModule("  " + LINE + "\n"));

    assertNull(refusal, () -> "refused: " + refusal);
  }
}
