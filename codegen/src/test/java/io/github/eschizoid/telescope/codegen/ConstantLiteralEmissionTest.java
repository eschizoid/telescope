package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An annotation carries its value as a string, and the processor has to turn that string into a
 * Java literal valid at the field's declared type. The suffix and the cast are the part that can go
 * wrong silently: {@code 42} assigned to a {@code long} field compiles either way, but {@code 1.5}
 * without the {@code f} does not compile at a {@code float}, and {@code 7} without the cast does
 * not compile at a {@code short}.
 *
 * <p>These compile through the full pipeline, so a literal that is wrong for its type fails here
 * rather than being asserted as a string and never attributed.
 */
class ConstantLiteralEmissionTest {

  private static Stream<Arguments> types() {
    return Stream.of(
      Arguments.of("boolean", "true", "true"),
      Arguments.of("Boolean", "true", "true"),
      Arguments.of("int", "42", "42"),
      Arguments.of("Integer", "42", "42"),
      Arguments.of("long", "42", "42L"),
      Arguments.of("Long", "42", "42L"),
      Arguments.of("short", "7", "(short) 7"),
      Arguments.of("Short", "7", "(short) 7"),
      Arguments.of("byte", "7", "(byte) 7"),
      Arguments.of("Byte", "7", "(byte) 7"),
      Arguments.of("double", "1.5", "1.5"),
      Arguments.of("Double", "1.5", "1.5"),
      Arguments.of("float", "1.5", "1.5f"),
      Arguments.of("Float", "1.5", "1.5f"),
      Arguments.of("char", "x", "'x'"),
      Arguments.of("Character", "x", "'x'"),
      Arguments.of("String", "hi", "\"hi\"")
    );
  }

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  @ParameterizedTest(name = "{0} <- \"{1}\" emits {2}")
  @MethodSource("types")
  @DisplayName("a @Constant string becomes the literal its target field's type requires")
  void constantBecomesATypedLiteral(final String type, final String value, final String literal) {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.KSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Constant;
        @Bridge(value = demo.KDst.class, constants = @Constant(field = "v", value = "%s"))
        public record KSrc(String name) {}
        """.formatted(value)
      ),
      ProcessorHarness.source("demo.KDst", "package demo; public record KDst(String name, %s v) {}".formatted(type))
    );

    assertTrue(
      compilation.success(),
      () -> "a valid " + type + " constant must compile: " + compilation.errorMessages()
    );
    final var bridge = compilation.generated().get("demo.KSrcBridge");
    assertNotNull(bridge);
    assertTrue(
      bridge.contains("new demo.KDst(__fs_name, " + literal + ")"),
      () -> "expected the " + type + " slot to take " + literal + ": " + bridge
    );
  }
}
