package io.github.eschizoid.telescope.codegen;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Runs compiled sources as an application module beside telescope's own modules, the way a module
 * path application loads them. Each module is a module of its own, so readability, exports and
 * opens apply between them exactly as they do on {@code java --module-path}.
 */
final class ModuleLayers {

  private ModuleLayers() {}

  /**
   * The modular entries of this test's class path, which is where telescope's modules come from.
   */
  static String modulePath() {
    return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
      .filter(ModuleLayers::isModular)
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

  /**
   * A layer holding the module {@code root} compiled by {@code compilation} and every telescope
   * module it requires, loaded together, as the application class loader loads a module path.
   */
  static ModuleLayer layer(final ProcessorHarness.Compilation compilation, final String root) throws IOException {
    final var dir = Files.createTempDirectory("module-layer");
    for (final var entry : compilation.classes().entrySet()) {
      final var file = dir.resolve(entry.getKey().replace('.', '/') + ".class");
      Files.createDirectories(file.getParent());
      Files.write(file, entry.getValue());
    }
    final var entries = new ArrayList<Path>();
    entries.add(dir);
    for (final var entry : modulePath().split(File.pathSeparator)) entries.add(Path.of(entry));
    final var configuration = ModuleLayer.boot()
      .configuration()
      .resolve(ModuleFinder.of(entries.toArray(Path[]::new)), ModuleFinder.of(), Set.of(root));
    return ModuleLayer.boot().defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
  }
}
