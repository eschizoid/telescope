package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The generated bridge calls a {@code @ViaMapper}'s {@code forward} and {@code backward} on the
 * mapper's class, so a method the class declares only on its instances is one the bridge cannot
 * call, generic or not. Such a mapper is refused by name, never written into a call that does not
 * compile.
 */
class ViaMapperInstanceMethodTest {

  @ParameterizedTest(name = "{0}")
  @ValueSource(
    strings = {
      "public <T> T forward(final T t) { return t; }",
      "public java.util.List<String> forward(final java.util.ArrayList<String> t) { return t; }",
      "@SuppressWarnings(\"unchecked\") public <T extends CharSequence> java.util.List<T> forward(" +
        "final java.util.ArrayList<String> t) { return (java.util.List<T>) (java.util.List<?>) t; }",
    }
  )
  @DisplayName("a forward declared on the mapper's instances is refused by name")
  void anInstanceForwardIsRefusedByName(final String forward) {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(
        "demo.ISrc",
        "package demo;\n" +
          "@io.github.eschizoid.telescope.annotations.Bridge(value = IDst.class, viaMappers =" +
          " @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"child\", using = IMap.class))\n" +
          "public record ISrc(java.util.ArrayList<String> child) {}\n"
      ),
      ProcessorHarness.source("demo.IDst", "package demo; public record IDst(java.util.List<String> child) {}"),
      ProcessorHarness.source(
        "demo.IMap",
        "package demo;\npublic class IMap {\n  " +
          forward +
          "\n  public static java.util.ArrayList<String> backward(final java.util.List<String> l) {" +
          " return new java.util.ArrayList<>(l); }\n}\n"
      )
    );
    assertFalse(compilation.success(), "an instance forward cannot be called from the bridge");
    assertTrue(
      compilation.hasError("@ViaMapper field=\"child\": demo.IMap declares no forward"),
      compilation::errorMessages
    );
    assertTrue(
      compilation
        .errorMessages()
        .lines()
        .noneMatch(line -> line.contains("static context")),
      compilation::errorMessages
    );
  }
}
