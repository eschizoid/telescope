package io.github.eschizoid.telescope.codegen;

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
 * A {@code constant} row's value is Java source the processor copies into a class it writes, which
 * returns it as the target property's boxed type. The copy has to stay a valid expression of that
 * type: a {@code long} keeps its suffix, a {@code float} its {@code f}, and a {@code short} or a
 * {@code byte}, which no {@code int} literal converts to in a method's argument, its cast.
 *
 * <p>These compile through the full pipeline, so a copied value that is wrong for its type fails
 * here rather than being asserted as a string and never attributed.
 */
class ConstantLiteralEmissionTest {

  private static Stream<Arguments> types() {
    return Stream.of(
      Arguments.of("boolean", "true", "true"),
      Arguments.of("Boolean", "true", "true"),
      Arguments.of("int", "42", "42"),
      Arguments.of("Integer", "42", "42"),
      Arguments.of("int", "-42", "-42"),
      Arguments.of("long", "42L", "42L"),
      Arguments.of("Long", "42L", "42L"),
      Arguments.of("short", "(short) 7", "((short)7)"),
      Arguments.of("Short", "(short) 7", "((short)7)"),
      Arguments.of("byte", "(byte) 7", "((byte)7)"),
      Arguments.of("Byte", "(byte) -7", "((byte)-7)"),
      Arguments.of("short", "(short) -0x7", "((short)-7)"),
      Arguments.of("double", "1.5", "1.5"),
      Arguments.of("Double", "1.5", "1.5"),
      Arguments.of("float", "1.5f", "1.5F"),
      Arguments.of("Float", "1.5f", "1.5F"),
      Arguments.of("char", "'x'", "'x'"),
      Arguments.of("Character", "'x'", "'x'"),
      Arguments.of("String", "\"hi\"", "\"hi\""),
      Arguments.of("String", "null", "null")
    );
  }

  private static Compilation compile(final String type, final String value) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), pair(type, value));
  }

  /**
   * {@code demo.KSrc}, whose {@code @Bridge} field fills {@code KDst.v}, of {@code type}, with
   * {@code value}.
   */
  private static JavaFileObject[] pair(final String type, final String value) {
    return new JavaFileObject[] {
      ProcessorHarness.source(
        "demo.KSrc",
        """
        package demo;
        import static io.github.eschizoid.telescope.mapping.Mapping.constant;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.conversion.Mapper;
        public record KSrc(String name) {
          @Bridge
          public static final Mapper<KSrc, KDst> MAPPER = Telescope.mapper(KSrc.class, KDst.class, constant(KDst::v, %s));
        }
        """.formatted(value)
      ),
      ProcessorHarness.source("demo.KDst", "package demo; public record KDst(String name, %s v) {}".formatted(type)),
    };
  }

  @ParameterizedTest(name = "{0} <- {1}")
  @MethodSource("types")
  @DisplayName("a constant row's value is written as an expression its target property's type holds")
  void constantBecomesATypedLiteral(final String type, final String value, final String written) {
    final var compilation = compile(type, value);

    assertTrue(
      compilation.success(),
      () -> "a valid " + type + " constant must compile: " + compilation.errorMessages()
    );
    final var filled = compilation.generated().get("demo.KSrcToKDstBridgeVConstant");
    assertNotNull(filled, () -> "no class fills the constant: " + compilation.generated().keySet());
    assertTrue(
      filled.contains("return " + written + ";"),
      () -> "expected the " + type + " slot to take " + written + ": " + filled
    );
    final var bridge = compilation.generated().get("demo.KSrcToKDstBridge");
    assertNotNull(bridge);
    assertTrue(
      bridge.contains("new demo.KDst(__fs_name, "),
      () -> "the bridge should rebuild the target with the constant: " + bridge
    );
  }

  @Test
  @DisplayName("a null constant for a primitive property is refused on the row, by the property's name")
  void aNullForAPrimitiveIsRefused() {
    final var compilation = compile("int", "null");

    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError("the value null cannot be written into the property 'v', which holds a primitive"),
      compilation::errorMessages
    );
  }
}
