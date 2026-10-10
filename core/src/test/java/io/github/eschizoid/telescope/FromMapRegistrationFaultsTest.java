package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.FromMapProcessor;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import io.github.eschizoid.telescope.conversion.FromMapProvider;
import io.github.eschizoid.telescope.introspection.OpticNode.Extracted;
import io.github.eschizoid.telescope.introspection.OpticNode.WhenAbsent;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code META-INF/services} registration for {@code FromMapProvider} can be broken by something
 * that has nothing to do with the type a mapper asks about: an entry naming a class that is not
 * there, or a provider that cannot say what it builds. Each case here builds a class loader of its
 * own over a compiled directory, so the registrations it sees are exactly the ones the test wrote.
 */
class FromMapRegistrationFaultsTest {

  private static final String SERVICE = "META-INF/services/" + FromMapProvider.class.getName();

  /**
   * A class loader over a freshly compiled {@code faults} package and the given registration. The
   * package goes through the {@code @FromMap} processor, so {@code Reader} has a generated binder;
   * the registration alone decides whether the runtime finds it.
   */
  private static URLClassLoader loaderWith(final String registration) throws IOException {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new FromMapProcessor()),
      List.of(),
      ProcessorHarness.source("faults.Probe", "package faults;\npublic record Probe(String city) {}\n"),
      ProcessorHarness.source("faults.Other", "package faults;\npublic record Other(String city) {}\n"),
      ProcessorHarness.source("faults.Holder", "package faults;\npublic record Holder(Probe probe) {}\n"),
      ProcessorHarness.source(
        "faults.Rowed",
        "package faults;\nimport static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;\n" +
          "public final class Rowed {\n  public static Object run(final java.util.Map<String, Object> map) {\n" +
          "    return io.github.eschizoid.telescope.Telescope.fromMap(Probe.class, " +
          "extract(\"town\", Probe::city, Object::toString)).forward(map);\n  }\n}\n"
      ),
      ProcessorHarness.source(
        "faults.Reader",
        "package faults;\n@io.github.eschizoid.telescope.annotations.FromMap(required = { \"length\", \"origin\" })\n" +
          "public class Reader {\n  private String name;\n" +
          "  public String getName() { return name; }\n" +
          "  public void setName(final String name) { this.name = name; }\n" +
          "  public int getLength() { return name == null ? 0 : name.length(); }\n" +
          "  public Other getOrigin() { return new Other(name); }\n}\n"
      ),
      ProcessorHarness.source(
        "faults.ProbeProvider",
        "package faults;\npublic final class ProbeProvider implements " +
          FromMapProvider.class.getName() +
          " {\n  public Class<?> targetType() { return Probe.class; }\n}\n"
      ),
      ProcessorHarness.source(
        "faults.ThrowingProvider",
        "package faults;\npublic final class ThrowingProvider implements " +
          FromMapProvider.class.getName() +
          " {\n  public Class<?> targetType() { throw new IllegalStateException(\"no target\"); }\n}\n"
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var dir = Files.createTempDirectory("frommap-faults");
    for (final var entry : compilation.classes().entrySet()) {
      final var file = dir.resolve(entry.getKey().replace('.', '/') + ".class");
      Files.createDirectories(file.getParent());
      Files.write(file, entry.getValue());
    }
    final var service = dir.resolve(SERVICE);
    Files.createDirectories(service.getParent());
    Files.writeString(service, registration);
    return new URLClassLoader(new URL[] { dir.toUri().toURL() }, FromMapRegistrationFaultsTest.class.getClassLoader());
  }

  private static Optional<String> verdict(final URLClassLoader loader, final String type)
    throws ClassNotFoundException {
    final Class<?> probe = loader.loadClass(type);
    assertEquals(loader, probe.getClassLoader(), "the type comes from the test's own loader");
    return FromMapCoercions.reasonFor(probe);
  }

  @Test
  @DisplayName("an entry naming a missing class and a provider that throws are skipped, and the good one still counts")
  void brokenEntriesAreSkipped() throws Exception {
    try (final var loader = loaderWith("faults.Missing\nfaults.ThrowingProvider\nfaults.ProbeProvider\n")) {
      assertEquals(Optional.empty(), verdict(loader, "faults.Probe"), "the registered type is accepted");
      final var other = verdict(loader, "faults.Other");
      assertTrue(other.orElseThrow().contains("faults.Other has no registered @FromMap binder"), other::orElseThrow);
    }
  }

  @Test
  @DisplayName("a registration with only broken entries refuses with the ordinary message instead of throwing")
  void onlyBrokenEntriesRefuseNormally() throws Exception {
    try (final var loader = loaderWith("faults.Missing\nfaults.ThrowingProvider\n")) {
      final var probe = verdict(loader, "faults.Probe");
      assertTrue(probe.orElseThrow().contains("faults.Probe has no registered @FromMap binder"), probe::orElseThrow);
    }
  }

  @Test
  @DisplayName("a provider that does not override binder() refuses the mapper that needs it, naming the component")
  void aProviderWithoutBinderRefusesTheComponent() throws Exception {
    try (final var loader = loaderWith("faults.ProbeProvider\n")) {
      final var holder = loader.loadClass("faults.Holder");
      final var refusal = assertThrows(IllegalArgumentException.class, () -> Telescope.fromMap(holder));
      assertEquals(
        "Telescope.fromMap: component 'probe' of Holder is declared Probe and no row names it: a FromMapProvider" +
          " that does not override binder() cannot build faults.Probe; regenerate its binder with the current" +
          " telescope-codegen, or implement both binder() and required()",
        refusal.getMessage()
      );
      assertInstanceOf(UnsupportedOperationException.class, refusal.getCause());
    }
  }

  @Test
  @DisplayName("a provider that does not override required() refuses a mapper over its own type")
  void aProviderWithoutRequiredRefusesItsTarget() throws Exception {
    try (final var loader = loaderWith("faults.ProbeProvider\n")) {
      final var probe = loader.loadClass("faults.Probe");
      final var refusal = assertThrows(IllegalArgumentException.class, () -> Telescope.fromMap(probe));
      assertTrue(
        refusal
          .getMessage()
          .startsWith(
            "Telescope.fromMap: Probe has a registered @FromMap binder, but a FromMapProvider" +
              " that does not override required() cannot say which keys faults.Probe requires"
          ),
        refusal::getMessage
      );
      assertInstanceOf(UnsupportedOperationException.class, refusal.getCause());
    }
  }

  @Test
  @DisplayName("a required key on a read-only property is checked for presence alone, by the binder and the runtime")
  void aRequiredReadOnlyPropertyKeepsItsRequirement() throws Exception {
    try (final var loader = loaderWith("faults.ReaderFromMap$Provider\n")) {
      final var reader = loader.loadClass("faults.Reader");
      final var binder = loader.loadClass("faults.ReaderFromMap").getMethod("fromMap", Map.class);
      final var mapper = Telescope.fromMap(reader);
      final Function<Map<String, Object>, Object> generated = map -> {
        try {
          return binder.invoke(null, map);
        } catch (final InvocationTargetException e) {
          throw (RuntimeException) e.getCause();
        } catch (final IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      };

      for (final var missing : List.<Map.Entry<Map<String, Object>, String>>of(
        Map.entry(Map.of("name", "Ada"), "keys \"length\" (property 'length'), \"origin\" (property 'origin')"),
        Map.entry(Map.of("name", "Ada", "length", "x"), "key \"origin\" (property 'origin')")
      )) {
        final var suffix = "the map carries no value for required " + missing.getValue() + " of Reader";
        final var runtime = assertThrows(IllegalArgumentException.class, () -> mapper.forward(missing.getKey()));
        assertEquals("Telescope.fromMap: " + suffix, runtime.getMessage());
        final var bound = assertThrows(IllegalArgumentException.class, () -> generated.apply(missing.getKey()));
        assertEquals("ReaderFromMap.fromMap: " + suffix, bound.getMessage());
      }
      // Neither "x" nor "y" converts to the property's type, so a path that read either value
      // would throw rather than build.
      final var present = Map.<String, Object>of("name", "Ada", "length", "x", "origin", "y");
      for (final var built : List.of(mapper.forward(present), generated.apply(present))) {
        assertEquals(
          List.of("Ada", 3, "Other[city=Ada]"),
          List.of(
            reader.getMethod("getName").invoke(built),
            reader.getMethod("getLength").invoke(built),
            reader.getMethod("getOrigin").invoke(built).toString()
          )
        );
      }
      assertEquals(
        List.of(
          new Extracted("name", "name", "String", WhenAbsent.DEFAULTS),
          new Extracted("length", "length", "int", WhenAbsent.REFUSES),
          new Extracted("origin", "origin", "Other", WhenAbsent.REFUSES)
        ),
        mapper.explain().extractions()
      );
    }
  }

  @Test
  @DisplayName("a mapper whose rows all read other keys never asks a provider without required()")
  void aFullyRowedMapperDoesNotAskForRequiredKeys() throws Exception {
    try (final var loader = loaderWith("faults.ProbeProvider\n")) {
      final var built = loader
        .loadClass("faults.Rowed")
        .getMethod("run", Map.class)
        .invoke(null, Map.of("town", "Oslo"));
      assertEquals("Oslo", built.getClass().getMethod("city").invoke(built));
    }
  }
}
