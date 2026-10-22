package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the compile-time verifier and mapper construction to one answer on whether a nested pair
 * converts through its own {@code @Bridge} or is walked, which decides the pairs a row can be keyed
 * to. Construction lets the bridge stand in unless something the mapper is given reaches below the
 * properties the bridge walks itself; the verifier reads those properties from the {@code @Bridge}
 * in the same compilation and asks the same shared question. A row reachable only through a
 * property the bridge converts its own way is then refused by both with one message, and a row the
 * bridge's walked properties reach makes both walk the pair and accept the row.
 *
 * <p>Each case compiles its sources once with the bridge processor and the verifier reporting
 * warnings, then builds the mapper those classes describe. Every case names its classes with a
 * prefix of its own, because one loader holds them all.
 */
class OwnBridgeAgreementTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final String HEADER = """
    package io.github.eschizoid.telescope.codegen;
    import static io.github.eschizoid.telescope.mapping.Mapping.*;
    import io.github.eschizoid.telescope.Telescope;
    import io.github.eschizoid.telescope.annotations.*;
    import io.github.eschizoid.telescope.conversion.BridgeFn;
    import io.github.eschizoid.telescope.mapping.NullHint;
    import io.github.eschizoid.telescope.mapping.WriteHint;
    import java.util.function.Supplier;
    record @@Part(String v) {}
    record @@PartDto(String v) {}
    """;

  /** A bridge for the nested pair transforming {@code part}, so the bridge does not walk it. */
  private static final String TRANSFORM_PART = """
    final class @@PartFn implements BridgeFn<@@Part, @@PartDto> {
      public @@PartDto forward(final @@Part p) { return p == null ? null : new @@PartDto(p.v()); }
      public @@Part backward(final @@PartDto p) { return p == null ? null : new @@Part(p.v()); }
    }
    """;

  /** A nested pair holding the row's pair under {@code part}, below a pair holding it. */
  private static final String NESTED = """
    record @@Outer(@@Inner in) {}
    record @@OuterDto(@@InnerDto in) {}
    record @@InnerDto(String name, @@PartDto part) {}
    """;

  /** The call every nested case makes: a row keyed to the pair only {@code part} leads to. */
  private static final String CALL =
    "Telescope.mapperForward(@@Outer.class, @@OuterDto.class, to(@@Part::v, @@PartDto::v))";

  private record Outcome(List<String> warnings, String construction) {}

  /**
   * Compiles {@code code} with the bridge processor and the verifier reporting warnings, defines
   * what it compiled, and calls the holder's {@code build()}: every warning, and the message
   * construction refused the mapper with, or null when it built.
   */
  private static Outcome outcome(final String prefix, final String code) {
    final var compilation = compileFully(
      List.of(new BridgeProcessor(), new MapperVerifierProcessor()),
      // The classes of one case are auxiliary classes javac warns of on every use, past its
      // default cap of a hundred warnings.
      List.of("-Atelescope.verify=warn", "-Xmaxwarns", "100000"),
      source(PACKAGE + "." + prefix + "Holder", (HEADER + code).replace("@@", prefix))
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var warnings = warnings(compilation);
    final var holder = compilation.define(MethodHandles.lookup()).get(PACKAGE + "." + prefix + "Holder");
    try {
      holder.getDeclaredMethod("build").invoke(null);
      return new Outcome(warnings, null);
    } catch (final InvocationTargetException e) {
      return new Outcome(warnings, e.getCause().getMessage());
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> warnings(final ProcessorHarness.Compilation compilation) {
    return compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .filter(m -> !m.startsWith("auxiliary class"))
      .toList();
  }

  /** A holder whose {@code build()} returns {@code call}. */
  private static String holder(final String call) {
    return "class @@Holder { static Object build() { return " + call + "; } }\n";
  }

  private static void assertBothRefuse(final Outcome outcome) {
    assertNotNull(outcome.construction(), "construction built the mapper");
    assertTrue(outcome.construction().contains("never visits"), outcome.construction());
    assertEquals(List.of(outcome.construction()), outcome.warnings());
  }

  private static void assertBothAccept(final Outcome outcome) {
    assertNull(outcome.construction(), outcome::construction);
    assertEquals(List.of(), outcome.warnings());
  }

  @Nested
  @DisplayName("A row reachable only through a property the nested pair's own bridge converts is refused by both")
  class ThroughAConvertedProperty {

    @Test
    @DisplayName("a property the bridge transforms")
    void transformed() {
      assertBothRefuse(
        outcome(
          "ObA",
          NESTED +
            "class @@PartFn implements BridgeFn<@@Part, @@PartDto> {\n" +
            "  public @@PartDto forward(final @@Part p) { return p == null ? null : new @@PartDto(p.v()); }\n" +
            "  public @@Part backward(final @@PartDto p) { return p == null ? null : new @@Part(p.v()); }\n" +
            "}\n" +
            "@Bridge(value = @@InnerDto.class, transforms = @Transform(field = \"part\", using = @@PartFn.class))\n" +
            "record @@Inner(String name, @@Part part) {}\n" +
            holder(CALL)
        )
      );
    }

    @Test
    @DisplayName("a property the bridge converts through a @ViaMapper")
    void viaMapper() {
      assertBothRefuse(
        outcome(
          "ObB",
          NESTED +
            "final class @@PartMap {\n" +
            "  public static @@PartDto forward(final @@Part p) { return p == null ? null : new @@PartDto(p.v()); }\n" +
            "  public static @@Part backward(final @@PartDto p) { return p == null ? null : new @@Part(p.v()); }\n" +
            "}\n" +
            "@Bridge(value = @@InnerDto.class, viaMappers = @ViaMapper(field = \"part\", using = @@PartMap.class))\n" +
            "record @@Inner(String name, @@Part part) {}\n" +
            holder(CALL)
        )
      );
    }

    @Test
    @DisplayName("a property the bridge drops, on a pair on a cycle whose bridge takes the active path")
    void onACycle() {
      assertBothRefuse(
        outcome(
          "ObC",
          """
            record @@Tree(String n, @@Branch b) {}
            record @@TreeDto(String n, @@BranchDto b) {}
            @Bridge(value = @@BranchDto.class, drops = "part", lenient = true)
            record @@Branch(@@Tree back, @@Part part) {}
            record @@BranchDto(@@TreeDto back, @@PartDto part) {}
            """ +
            holder("Telescope.mapperForward(@@Tree.class, @@TreeDto.class, to(@@Part::v, @@PartDto::v))")
        )
      );
    }
  }

  @Test
  @DisplayName("a row below a property the bridge drops and computes again is refused by both")
  void computedTarget() {
    assertBothRefuse(
      outcome(
        "ObG",
        """
          record @@Outer(@@Inner in) {}
          record @@OuterDto(@@InnerDto in) {}
          record @@InnerDto(@@PartDto part, @@Part spare) {}
          final class @@PartMaker implements Supplier<@@PartDto> {
            public @@PartDto get() { return new @@PartDto("made"); }
          }
          @Bridge(value = @@InnerDto.class, drops = "part", computes = @Compute(field = "part", using = @@PartMaker.class))
          record @@Inner(@@Part part, @@Part spare) {}
          """ +
          holder(CALL)
      )
    );
  }

  @Nested
  @DisplayName("Where the verifier cannot tell which bridge construction takes, both walk the pair")
  class Unsettled {

    @Test
    @DisplayName("a call given a null hint, which reaches every pair")
    void nullHinted() {
      assertBothAccept(
        outcome(
          "ObI",
          NESTED +
            TRANSFORM_PART +
            "@Bridge(value = @@InnerDto.class, transforms = @Transform(field = \"part\", using = @@PartFn.class))\n" +
            "record @@Inner(String name, @@Part part) {}\n" +
            holder(
              "Telescope.mapperForward(@@Outer.class, @@OuterDto.class, to(@@Part::v, @@PartDto::v), " +
                "NullHint.nullSourceValues(NullHint.NullStrategy.DEFAULT))"
            )
        )
      );
    }

    @Test
    @DisplayName("a call given a default write strategy, which reaches every pair")
    void writeHinted() {
      assertBothAccept(
        outcome(
          "ObL",
          NESTED +
            TRANSFORM_PART +
            "@Bridge(value = @@InnerDto.class, transforms = @Transform(field = \"part\", using = @@PartFn.class))\n" +
            "record @@Inner(String name, @@Part part) {}\n" +
            holder(
              "Telescope.mapperForward(@@Outer.class, @@OuterDto.class, to(@@Part::v, @@PartDto::v), " +
                "WriteHint.writeBeans(WriteHint.WriteStrategy.SETTERS))"
            )
        )
      );
    }

    @Test
    @DisplayName("a pair two bridges on its source name, the bridge generated from the last walking the property")
    void namedTwice() {
      assertBothAccept(
        outcome(
          "ObJ",
          NESTED +
            TRANSFORM_PART +
            "@Bridge(value = @@InnerDto.class, transforms = @Transform(field = \"part\", using = @@PartFn.class))\n" +
            "@Bridge(@@InnerDto.class)\n" +
            "record @@Inner(String name, @@Part part) {}\n" +
            holder(CALL)
        )
      );
    }

    @Test
    @DisplayName("a pair a carrier class names, whose bridge construction finds through its registration")
    void carrier() {
      assertBothAccept(
        outcome(
          "ObK",
          NESTED +
            TRANSFORM_PART +
            "record @@Inner(String name, @@Part part) {}\n" +
            "@Bridge(source = @@Inner.class, target = @@InnerDto.class, " +
            "transforms = @Transform(field = \"part\", using = @@PartFn.class))\n" +
            "final class @@Carrier {}\n" +
            holder(CALL)
        )
      );
    }
  }

  @Nested
  @DisplayName("A row the nested pair's own bridge would pass over makes both walk the pair")
  class PassedOver {

    @Test
    @DisplayName("a row below a property the bridge renames another property beside")
    void renameElsewhere() {
      assertBothAccept(
        outcome(
          "ObD",
          """
            record @@Outer(@@Inner in) {}
            record @@OuterDto(@@InnerDto in) {}
            record @@InnerDto(String title, @@PartDto part) {}
            @Bridge(value = @@InnerDto.class, renames = @Rename(source = "name", target = "title"))
            record @@Inner(String name, @@Part part) {}
            """ +
            holder(CALL)
        )
      );
    }

    @Test
    @DisplayName("a row below a property a lenient bridge walks")
    void lenientBridge() {
      assertBothAccept(
        outcome(
          "ObE",
          """
            record @@Outer(@@Inner in) {}
            record @@OuterDto(@@InnerDto in) {}
            record @@InnerDto(String name, @@PartDto part, String extra) {}
            @Bridge(value = @@InnerDto.class, lenient = true)
            record @@Inner(String name, @@Part part) {}
            """ +
            holder(CALL)
        )
      );
    }
  }

  @Test
  @DisplayName(
    "a row below a property the bridge renames onto another name is refused by both, the walk by name not reaching it"
  )
  void renamedLeadingProperty() {
    assertBothRefuse(
      outcome(
        "ObF",
        """
          record @@Outer(@@Inner in) {}
          record @@OuterDto(@@InnerDto in) {}
          record @@InnerDto(String name, @@PartDto piece) {}
          @Bridge(value = @@InnerDto.class, renames = @Rename(source = "part", target = "piece"))
          record @@Inner(String name, @@Part part) {}
          """ +
          holder(CALL)
      )
    );
  }

  /**
   * Where the verifier owes silence on a call construction refuses, each with the property that
   * keeps the verifier from knowing.
   */
  private static final Map<String, String> VERIFIER_LIMITS = Map.of(
    // Inherent: @Bridge is kept in source only, and the bridge generated from it holds
    // the properties it does not walk only in its method bodies, so a bridge compiled
    // apart from the call leaves nothing the verifier can read about which properties
    // it converts.
    "a row reachable only through a property a nested pair's own bridge, compiled apart, converts",
    "never visits"
  );

  @TempDir
  Path dir;

  @Test
  @DisplayName("the verifier says nothing where the nested pair's own bridge was compiled apart from the call")
  void bridgeCompiledApart() throws Exception {
    final var refusal = VERIFIER_LIMITS.get(
      "a row reachable only through a property a nested pair's own bridge, compiled apart, converts"
    );
    final var a = jar(
      "a",
      compileFully(
        List.of(new BridgeProcessor()),
        List.of(),
        source("oba.Part", "package oba;\npublic record Part(String v) {}\n"),
        source("oba.PartDto", "package oba;\npublic record PartDto(String v) {}\n"),
        source(
          "oba.Inner",
          "package oba;\n" +
            "@io.github.eschizoid.telescope.annotations.Bridge(value = InnerDto.class, drops = \"part\", lenient = true)\n" +
            "public record Inner(String name, Part part) {}\n"
        ),
        source("oba.InnerDto", "package oba;\npublic record InnerDto(String name, PartDto part) {}\n"),
        source("oba.Outer", "package oba;\npublic record Outer(Inner in) {}\n"),
        source("oba.OuterDto", "package oba;\npublic record OuterDto(InnerDto in) {}\n")
      )
    );
    final var calls = compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of("-Atelescope.verify=warn", "-classpath", System.getProperty("java.class.path") + File.pathSeparator + a),
      source(
        "obc.Calls",
        "package obc;\n" +
          "import static io.github.eschizoid.telescope.mapping.Mapping.to;\n" +
          "import io.github.eschizoid.telescope.Telescope;\n" +
          "public final class Calls {\n" +
          "  public static Object build() {\n" +
          "    return Telescope.mapperForward(oba.Outer.class, oba.OuterDto.class, to(oba.Part::v, oba.PartDto::v));\n" +
          "  }\n" +
          "}\n"
      )
    );
    assertEquals(List.of(), warnings(calls));
    final var c = jar("c", calls);
    try (
      final var loader = new URLClassLoader(
        new URL[] { a.toUri().toURL(), c.toUri().toURL() },
        getClass().getClassLoader()
      )
    ) {
      final var thrown = assertThrows(InvocationTargetException.class, () ->
        loader.loadClass("obc.Calls").getMethod("build").invoke(null)
      );
      assertTrue(thrown.getCause().getMessage().contains(refusal), thrown.getCause()::getMessage);
    }
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
}
