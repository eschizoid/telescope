package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Three modules compiled apart, as separate builds compile them: {@code a} declares a record and a
 * bean whose setter takes a different type than its getter gives, {@code b} declares a
 * {@code @Bridge} field for that pair, on a class of its own, that converts the property by a row,
 * and {@code c} calls {@code Telescope.mapperForward} on the pair and on a pair holding it. Each
 * module is compiled into a jar the next one has on its class path.
 *
 * <p>The runtime finds the carrier's bridge through its service registration and never writes the
 * property through that setter, so the verifier compiling {@code c} owes silence. Without the
 * carrier on the class path the runtime refuses the setter, and the verifier owes the same refusal.
 */
class CarrierOnClassPathTest {

  private static final String SETTER_REFUSAL = "setter setX(java.lang.String)";

  @TempDir
  Path dir;

  private static JavaFileObject source(final String fqcn, final String code) {
    return ProcessorHarness.source(fqcn, code);
  }

  /** The compiling JVM's own class path followed by {@code jars}. */
  private static List<String> classPath(final Path... jars) {
    final var entries = new ArrayList<String>();
    entries.add(System.getProperty("java.class.path"));
    for (final var jar : jars) entries.add(jar.toString());
    return List.of("-classpath", String.join(File.pathSeparator, entries));
  }

  /**
   * The compiling JVM's own class path followed by {@code jar} as the class path, and {@code
   * modules} as the module path, every module on it resolved.
   */
  private static List<String> modulePath(final Path jar, final Path modules) {
    final var options = new ArrayList<>(classPath(jar));
    options.addAll(List.of("--module-path", modules.toString(), "--add-modules", "ALL-MODULE-PATH"));
    return options;
  }

  /** Every entry of {@code jars}, in order, written into one jar named {@code name}. */
  private Path merged(final String name, final Path... jars) throws IOException {
    final var merged = dir.resolve(name + ".jar");
    try (final var out = new JarOutputStream(Files.newOutputStream(merged))) {
      for (final var jar : jars) {
        try (final var in = new JarFile(jar.toFile())) {
          for (final var entry : Collections.list(in.entries())) {
            out.putNextEntry(new JarEntry(entry.getName()));
            out.write(in.getInputStream(entry).readAllBytes());
            out.closeEntry();
          }
        }
      }
    }
    return merged;
  }

  /** {@code compilation}'s classes and resources, written into a jar named {@code name}. */
  private Path jar(final String name, final ProcessorHarness.Compilation compilation) throws IOException {
    assertTrue(compilation.success(), () -> name + " should compile: " + compilation.errorMessages());
    final var jar = dir.resolve(name + ".jar");
    try (final var out = new JarOutputStream(Files.newOutputStream(jar))) {
      for (final var entry : compilation.classes().entrySet()) {
        out.putNextEntry(new JarEntry(entry.getKey().replace('.', '/') + ".class"));
        out.write(entry.getValue());
        out.closeEntry();
      }
      for (final var entry : compilation.resources().entrySet()) {
        out.putNextEntry(new JarEntry(entry.getKey()));
        out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return jar;
  }

  private Path moduleA() throws IOException {
    return jar(
      "a",
      ProcessorHarness.compileFully(
        List.of(),
        List.of(),
        source("cm612.a.Src", "package cm612.a;\npublic record Src(int x, String name) {}\n"),
        source(
          "cm612.a.Bean",
          "package cm612.a;\n" +
            "public class Bean {\n" +
            "  private int x;\n" +
            "  private String name;\n" +
            "  public Bean() {}\n" +
            "  public int getX() { return x; }\n" +
            "  public void setX(final String x) { this.x = Integer.parseInt(x); }\n" +
            "  public String getName() { return name; }\n" +
            "  public void setName(final String name) { this.name = name; }\n" +
            "}\n"
        ),
        source("cm612.a.Holder", "package cm612.a;\npublic record Holder(Src inner) {}\n"),
        source("cm612.a.HolderDto", "package cm612.a;\npublic record HolderDto(Bean inner) {}\n"),
        source("cm612.a.Plain", "package cm612.a;\npublic record Plain(String v) {}\n"),
        source("cm612.a.PlainDto", "package cm612.a;\npublic record PlainDto(String v) {}\n")
      )
    );
  }

  private Path moduleB(final Path a) throws IOException {
    return jar(
      "b",
      ProcessorHarness.compileFully(
        List.of(new BridgeProcessor()),
        classPath(a),
        source(
          "cm612.b.Conv",
          "package cm612.b;\n" +
            "public final class Conv {\n" +
            "  public static String text(final int i) { return Integer.toString(i); }\n" +
            "}\n"
        ),
        source(
          "cm612.b.SrcToBean",
          "package cm612.b;\n" +
            "public final class SrcToBean {\n" +
            "  @io.github.eschizoid.telescope.annotations.Bridge\n" +
            "  public static final io.github.eschizoid.telescope.conversion.ForwardMapper<cm612.a.Src, cm612.a.Bean>" +
            " MAPPER = io.github.eschizoid.telescope.Telescope.mapperForward(cm612.a.Src.class, cm612.a.Bean.class," +
            " io.github.eschizoid.telescope.mapping.Mapping.<cm612.a.Src, cm612.a.Bean, Integer, Object>toOneWay(" +
            "cm612.a.Src::x, cm612.a.Bean::getX, Conv::text));\n" +
            "}\n"
        ),
        // A bridge on its own source, not a carrier, leaves no marker.
        source(
          "cm612.b.Own",
          "package cm612.b;\n" +
            "@io.github.eschizoid.telescope.annotations.Bridge(OwnDto.class)\n" +
            "public record Own(String v) {}\n"
        ),
        source("cm612.b.OwnDto", "package cm612.b;\npublic record OwnDto(String v) {}\n")
      )
    );
  }

  private static ProcessorHarness.Compilation moduleC(final List<String> classPath) {
    return ProcessorHarness.compileFully(
      List.of(new MapperVerifierProcessor()),
      classPath,
      source(
        "cm612.c.Calls",
        "package cm612.c;\n" +
          "import io.github.eschizoid.telescope.Telescope;\n" +
          "public final class Calls {\n" +
          "  public static Object root() { return Telescope.mapperForward(cm612.a.Src.class, cm612.a.Bean.class); }\n" +
          "  public static Object nested() {\n" +
          "    return Telescope.mapperForward(cm612.a.Holder.class, cm612.a.HolderDto.class);\n" +
          "  }\n" +
          "}\n"
      )
    );
  }

  /** The class named {@code name}, loaded through {@code loader}. */
  @SuppressWarnings("unchecked")
  private static Class<Object> load(final ClassLoader loader, final String name) throws ClassNotFoundException {
    return (Class<Object>) loader.loadClass(name);
  }

  /** How many of {@code compilation}'s errors are the setter refusal. */
  private static long setterRefusals(final ProcessorHarness.Compilation compilation) {
    return compilation
      .errors()
      .stream()
      .filter(e -> e.getMessage(null).contains(SETTER_REFUSAL))
      .count();
  }

  @Test
  @DisplayName("the bridge processor leaves a marker for a carrier pair, and for no other")
  void aCarrierPairLeavesAMarker() throws IOException {
    final var b = moduleB(moduleA());
    final var entries = new ArrayList<String>();
    try (final var jar = new JarFile(b.toFile())) {
      jar
        .stream()
        .map(JarEntry::getName)
        .filter(n -> n.startsWith(CarrierMarkers.DIRECTORY))
        .forEach(entries::add);
    }
    assertEquals(List.of(CarrierMarkers.relativeName("cm612.a.Src", "cm612.a.Bean")), entries);
  }

  @Test
  @DisplayName("a marker does not make its directory a package of a named module")
  void aMarkerIsNoPackageOfANamedModule() throws IOException {
    final var compiled = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      source("module-info", "module cm612.m {}\n"),
      source("cm612.m.K", "package cm612.m;\npublic final class K {}\n")
    );
    final var jar = jar("m", compiled);
    try (final var out = new JarOutputStream(Files.newOutputStream(dir.resolve("m-marked.jar")))) {
      try (final var in = new JarFile(jar.toFile())) {
        for (final var entry : Collections.list(in.entries())) {
          out.putNextEntry(new JarEntry(entry.getName()));
          out.write(in.getInputStream(entry).readAllBytes());
          out.closeEntry();
        }
      }
      out.putNextEntry(new JarEntry(CarrierMarkers.relativeName("cm612.a.Src", "cm612.a.Bean")));
      out.write("marker".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    final var module = ModuleFinder.of(dir.resolve("m-marked.jar")).find("cm612.m").orElseThrow();
    assertEquals(Set.of("cm612.m"), module.descriptor().packages());
  }

  @Test
  @DisplayName(
    "the verifier stays silent for a pair a carrier on the module path converts, in an automatic module and an explicit one"
  )
  void aCarrierOnTheModulePathKeepsTheVerifierSilent() throws IOException {
    final var a = moduleA();
    final var automatic = moduleB(a);
    final var descriptor = jar(
      "descriptor",
      ProcessorHarness.compileFully(List.of(), List.of(), source("module-info", "module cm612.carriers {}\n"))
    );
    final var explicit = merged("carriers", automatic, descriptor);
    assertTrue(ModuleFinder.of(automatic).find("b").orElseThrow().descriptor().isAutomatic());
    assertFalse(ModuleFinder.of(explicit).find("cm612.carriers").orElseThrow().descriptor().isAutomatic());
    final var failures = new ArrayList<String>();
    for (final var carrier : List.of(automatic, explicit)) {
      final var compiled = moduleC(modulePath(a, carrier));
      if (!compiled.success()) {
        failures.add(carrier.getFileName() + ": the verifier owed silence, saw " + compiled.errorMessages());
      }
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("the verifier stays silent for a pair a carrier on the class path converts, as the runtime builds it")
  void aCarrierOnTheClassPathKeepsTheVerifierSilent() throws Exception {
    final var a = moduleA();
    final var b = moduleB(a);
    final var withCarrier = moduleC(classPath(a, b));
    assertTrue(withCarrier.success(), () -> "the verifier owed silence, saw " + withCarrier.errorMessages());
    try (
      final var loader = new URLClassLoader(
        new URL[] { a.toUri().toURL(), b.toUri().toURL() },
        getClass().getClassLoader()
      )
    ) {
      final var src = load(loader, "cm612.a.Src");
      final var bean = load(loader, "cm612.a.Bean");
      final var built = Telescope.mapperForward(src, bean).forward(src.getConstructors()[0].newInstance(7, "n"));
      assertEquals(7, bean.getMethod("getX").invoke(built), "the runtime converts through the carrier's bridge");
      final var holder = load(loader, "cm612.a.Holder");
      final var holderDto = load(loader, "cm612.a.HolderDto");
      final var nested = Telescope.mapperForward(holder, holderDto).forward(
        holder.getConstructors()[0].newInstance(src.getConstructors()[0].newInstance(8, "m"))
      );
      final var inner = holderDto.getMethod("inner").invoke(nested);
      assertEquals(8, bean.getMethod("getX").invoke(inner), "the runtime converts the nested pair the same way");
    }
  }

  @Test
  @DisplayName("without the carrier on the class path the verifier reports the setter, as the runtime refuses it")
  void withoutTheCarrierTheVerifierReportsTheSetter() throws Exception {
    final var a = moduleA();
    final var withoutCarrier = moduleC(classPath(a));
    assertFalse(withoutCarrier.success(), "the verifier owed the setter refusal");
    assertEquals(2, setterRefusals(withoutCarrier), withoutCarrier::errorMessages);
    try (final var loader = new URLClassLoader(new URL[] { a.toUri().toURL() }, getClass().getClassLoader())) {
      final var src = load(loader, "cm612.a.Src");
      final var bean = load(loader, "cm612.a.Bean");
      final var refused = assertThrows(RuntimeException.class, () -> Telescope.mapperForward(src, bean));
      assertTrue(refused.getMessage().contains(SETTER_REFUSAL), refused::getMessage);
    }
  }
}
