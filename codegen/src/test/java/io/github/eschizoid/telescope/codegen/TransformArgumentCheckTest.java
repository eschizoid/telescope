package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A converting {@code to} row on a property declared with a type variable, which the bridge holds
 * as the variable's erasure, through a {@code BridgeFn} taking a class narrower than that erasure.
 * A class given no type arguments is handed the value after {@code FixedClass.argument} checks its
 * class, in each direction the row emits; any other is refused by the property's name.
 */
class TransformArgumentCheckTest {

  private static final String CHECK = "io.github.eschizoid.telescope.conversion.FixedClass.argument(";

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  /**
   * A {@code BridgeFn<type, type>} named {@code demo.Fn} that hands its argument back, with a static
   * {@code ahead} that does the same, forward only.
   */
  private static JavaFileObject fn(final String type) {
    return ProcessorHarness.source(
      "demo.Fn",
      """
      package demo;
      public final class Fn implements io.github.eschizoid.telescope.conversion.BridgeFn<%1$s, %1$s> {
        @Override public %1$s forward(final %1$s a) { return a; }
        @Override public %1$s backward(final %1$s b) { return b; }
        public static %1$s ahead(final %1$s a) { return a; }
      }
      """.formatted(type)
    );
  }

  /**
   * {@code demo.Src} declaring {@code value} as {@code declared}, with a {@code @Bridge} field
   * converting it into a matching target through {@code demo.Fn}, both ways, or through its {@code
   * ahead} on a {@code mapperForward} field. The row's type arguments name what the property holds
   * as {@code held}, since a method reference to a member of a generic class names it raw.
   */
  private static JavaFileObject[] pair(
    final String header,
    final String declared,
    final String held,
    final boolean forwardOnly
  ) {
    final var arguments = header.isEmpty() ? "" : "<" + held + ">";
    final var src = "Src" + arguments;
    final var tgt = "Tgt<" + held + ">";
    final var row = forwardOnly
      ? "Mapping.<%1$s, %2$s, %3$s, %3$s>toOneWay(%1$s::value, %2$s::value, Fn::ahead)"
      : "Mapping.<%1$s, %2$s, %3$s, %3$s>to(%1$s::value, %2$s::value, Fn.class)";
    return new JavaFileObject[] {
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.mapping.Mapping;
        public record Src%s(%s value) {
          @Bridge
          @SuppressWarnings({"rawtypes", "unchecked"})
          public static final io.github.eschizoid.telescope.conversion.%s<Src, Tgt> MAPPER = Telescope.%s(
            Src.class,
            Tgt.class,
            %s
          );
        }
        """.formatted(
            header,
            declared,
            forwardOnly ? "ForwardMapper" : "Mapper",
            forwardOnly ? "mapperForward" : "mapper",
            row.formatted(src, tgt, held)
          )
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt<Y>(Y value) {}"),
    };
  }

  /** The generated {@code method} of {@code demo.SrcToTgtBridge}, from its signature to the next. */
  private static String method(final Compilation compilation, final String method) {
    final var bridge = compilation.generated().get("demo.SrcToTgtBridge");
    final var start = bridge.indexOf(" " + method + "(final");
    final var end = bridge.indexOf("public static", start + 1);
    return bridge.substring(start, end < 0 ? bridge.length() : end);
  }

  @Test
  @DisplayName("a conversion taking a class narrower than the variable checks the value it reads in each direction")
  void aNarrowerClassIsCheckedInEachDirection() {
    final var sources = pair("<X>", "X", "String", false);
    final var compilation = compile(fn("String"), sources[0], sources[1]);
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(
      method(compilation, "forward").contains(
        "((java.lang.String) " + CHECK + "__fs_value, java.lang.String.class, demo.Src.class, \"value\"))"
      ),
      () -> method(compilation, "forward")
    );
    assertTrue(
      method(compilation, "backward").contains(
        "((java.lang.String) " + CHECK + "__bt_value, java.lang.String.class, demo.Tgt.class, \"value\"))"
      ),
      () -> method(compilation, "backward")
    );
    assertTrue(method(compilation, "patch").contains(CHECK), () -> method(compilation, "patch"));
  }

  @Test
  @DisplayName("a renamed property's backward check names the target property the rename names")
  void aRenamedPropertyIsCheckedByItsTargetName() {
    final var compilation = compile(
      fn("String"),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.conversion.Mapper;
        import io.github.eschizoid.telescope.mapping.Mapping;
        public record Src<X>(X value) {
          @Bridge
          @SuppressWarnings({"rawtypes", "unchecked"})
          public static final Mapper<Src, Tgt> MAPPER = Telescope.mapper(
            Src.class,
            Tgt.class,
            Mapping.<Src<String>, Tgt<String>, String, String>to(Src<String>::value, Tgt<String>::label, Fn.class)
          );
        }
        """
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt<Y>(Y label) {}")
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(
      method(compilation, "backward").contains(
        "((java.lang.String) " + CHECK + "__bt_value, java.lang.String.class, demo.Tgt.class, \"label\"))"
      ),
      () -> method(compilation, "backward")
    );
  }

  @Test
  @DisplayName("a forward-only row checks the read it emits and nothing for the backward it does not")
  void aForwardOnlyRowChecksOnlyForward() {
    final var sources = pair("<X>", "X", "String", true);
    final var compilation = compile(fn("String"), sources[0], sources[1]);
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(method(compilation, "forward").contains(CHECK), () -> method(compilation, "forward"));
    assertFalse(method(compilation, "backward").contains(CHECK), () -> method(compilation, "backward"));
  }

  @Test
  @DisplayName("a conversion taking a class given type arguments is refused by name, since a check sees only the class")
  void aClassGivenArgumentsIsRefused() {
    final var sources = pair("<X>", "X", "java.util.List<String>", false);
    final var compilation = compile(fn("java.util.List<String>"), sources[0], sources[1]);
    assertFalse(compilation.success());
    assertTrue(compilation.hasError("a converting row of 'value' does not fit forward"), compilation::errorMessages);
  }

  @Test
  @DisplayName("a property declared as a class, not a variable, does not take a narrower conversion's row")
  void aPropertyDeclaredAsAClassIsRefused() {
    final var sources = pair("", "Object", "String", false);
    final var compilation = compile(fn("String"), sources[0], sources[1]);
    assertFalse(compilation.success());
    assertTrue(compilation.generated().isEmpty(), () -> "no bridge is written: " + compilation.generated().keySet());
  }

  @Test
  @DisplayName("a conversion taking the variable's own erasure is handed the value as it is")
  void theErasureItselfIsNotChecked() {
    final var sources = pair("<X extends Number>", "X", "Number", false);
    final var compilation = compile(fn("Number"), sources[0], sources[1]);
    assertTrue(compilation.success(), compilation::errorMessages);
    assertEquals(-1, method(compilation, "forward").indexOf(CHECK), () -> method(compilation, "forward"));
    assertTrue(
      method(compilation, "backward").contains(
        "((java.lang.Number) " + CHECK + "__bt_value, java.lang.Number.class, demo.Tgt.class, \"value\"))"
      ),
      () -> method(compilation, "backward")
    );
  }
}
