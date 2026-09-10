package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The per-field modifier annotations carry meaning only as members of {@code @Bridge}'s attributes.
 * Written on a declaration instead, nothing reads them, so the mapping they describe never takes
 * effect and the build stays green — the failure mode a declaration-site restriction converts into
 * a compile error.
 *
 * <p>Each case supplies that annotation's own required elements, so the compile fails for
 * applicability and nothing else. The two assertions in the parameterized test below are what hold
 * a case to that; the note there says which one catches what.
 *
 * <p>These compile through the full javac pipeline rather than {@code -proc:only} for the sake of
 * the attribute-value case: under processing-only the emitted bridge body is never attributed, so a
 * bridge that generated uncompilable code would still pass.
 */
class ModifierAnnotationTargetTest {

  private static Stream<Arguments> modifiers() {
    return Stream.of(
      Arguments.of("Default", "field = \"region\", value = \"EMEA\"", ""),
      Arguments.of("Rename", "source = \"reference\", target = \"referenceCode\"", ""),
      Arguments.of("Transform", "field = \"region\", using = String.class", ""),
      Arguments.of("Constant", "field = \"region\", value = \"EMEA\"", ""),
      Arguments.of("Compute", "field = \"region\", using = Supplier.class", "import java.util.function.Supplier;"),
      Arguments.of("ViaMapper", "field = \"region\", using = String.class", "")
    );
  }

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  @ParameterizedTest(name = "@{0}")
  @MethodSource("modifiers")
  @DisplayName("a modifier annotation written on a declaration is rejected by javac")
  void standaloneUseIsRejected(final String annotation, final String attributes, final String extraImport) {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.Order",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.%s;
        %s
        @%s(%s)
        public record Order(String region, String reference) {}
        """.formatted(annotation, extraImport, annotation, attributes)
      )
    );

    assertFalse(compilation.success(), "@" + annotation + " on a type declaration must not compile");
    assertTrue(
      compilation.hasError("not applicable"),
      "javac should reject @" + annotation + " as inapplicable, got: " + compilation.errorMessages()
    );
    // Exactly one diagnostic, so nothing but applicability failed. The two assertions catch
    // different mis-specifications: leaving a required element unsupplied suppresses the
    // applicability diagnostic entirely, so only the substring assertion above sees that, while
    // a stray name alongside complete required elements, or a wrong value type, is reported
    // beside it, so only this count does.
    assertEquals(
      1,
      compilation.errors().size(),
      "expected only the applicability error, got: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("@Default and @Rename still reach the generated bridge as @Bridge attribute values")
  void memberValueUseStillCompiles() {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.Order",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Default;
        import io.github.eschizoid.telescope.annotations.Rename;
        @Bridge(
          value = OrderDto.class,
          defaults = @Default(field = "region", value = "EMEA"),
          renames = @Rename(source = "reference", target = "referenceCode")
        )
        public record Order(String region, String reference) {}
        """
      ),
      ProcessorHarness.source(
        "demo.OrderDto",
        """
        package demo;
        public record OrderDto(String region, String referenceCode) {}
        """
      )
    );

    assertTrue(compilation.success(), "attribute-value use must keep compiling: " + compilation.errorMessages());

    // Compiling is not enough: this source is valid Java whether or not the processor ran at all,
    // so the generated bridge is the artifact that shows the attributes were read. A missing
    // bridge means the processor emitted nothing; the rename failing instead breaks the bijection,
    // which the processor reports as an error the success assertion above already catches. The
    // default's literal appears only where @Default put it.
    final var bridge = compilation.generated().get("demo.OrderBridge");
    assertNotNull(bridge, "the processor must emit a bridge for the annotated pair");
    assertTrue(bridge.contains("\"EMEA\""), "@Default must reach the generated bridge");
  }
}
