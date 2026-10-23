package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A named module finds a service provider only through a {@code provides} directive, so a bridge a
 * {@code @Bridge} field declares in one is found by the field's mapper only when the module's
 * {@code module-info} provides it. The processor sees the module and says which line to add.
 */
class BridgeFieldModulePathTest {

  private static final String LINE =
    "provides io.github.eschizoid.telescope.conversion.BridgeProvider with demo.PointToPointDtoBridgeProvider;";

  private static ProcessorHarness.Compilation compileModule(final String directives) {
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of("--module-path", ModuleLayers.modulePath()),
      ProcessorHarness.source(
        "module-info",
        "module demo {\n  requires io.github.eschizoid.telescope;\n  exports demo;\n  opens demo;\n" +
          directives +
          "}\n"
      ),
      ProcessorHarness.source("demo.Point", "package demo; public record Point(String name) {}"),
      ProcessorHarness.source("demo.PointDto", "package demo; public record PointDto(String label) {}"),
      ProcessorHarness.source(
        "demo.Mappers",
        "package demo;\n" +
          "import static io.github.eschizoid.telescope.mapping.Mapping.to;\n" +
          "public final class Mappers {\n" +
          "  @io.github.eschizoid.telescope.annotations.Bridge\n" +
          "  public static final io.github.eschizoid.telescope.conversion.Mapper<Point, PointDto> POINT =\n" +
          "    io.github.eschizoid.telescope.Telescope.mapper(Point.class, PointDto.class, to(Point::name, PointDto::label));\n" +
          "}\n"
      )
    );
  }

  private static List<String> warnings(final ProcessorHarness.Compilation compilation) {
    return compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.MANDATORY_WARNING || d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .filter(m -> m.startsWith("@Bridge: module"))
      .toList();
  }

  @Test
  @DisplayName("a named module that does not provide its field bridges is told the provides line to add")
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
  void aModuleThatProvidesItsBridgesIsNotWarned() {
    final var compilation = compileModule("  " + LINE + "\n");
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(warnings(compilation).isEmpty(), () -> "warnings: " + warnings(compilation));
  }
}
