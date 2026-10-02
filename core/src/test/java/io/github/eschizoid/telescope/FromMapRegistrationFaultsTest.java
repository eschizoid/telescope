package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import io.github.eschizoid.telescope.conversion.FromMapProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
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

  /** A class loader over a freshly compiled {@code faults} package and the given registration. */
  private static URLClassLoader loaderWith(final String registration) throws IOException {
    final var compilation = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      ProcessorHarness.source("faults.Probe", "package faults;\npublic record Probe(String city) {}\n"),
      ProcessorHarness.source("faults.Other", "package faults;\npublic record Other(String city) {}\n"),
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
    return FromMapRefusals.reasonFor(probe);
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
}
