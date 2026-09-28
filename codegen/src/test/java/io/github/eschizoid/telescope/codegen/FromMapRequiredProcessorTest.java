package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What {@code @FromMap(required = ...)} accepts at compile time, and what it emits. */
class FromMapRequiredProcessorTest {

  @Test
  @DisplayName("a required name that is not a component is a compile error naming it and the components")
  void anUnknownRecordNameIsRefused() {
    final var compilation = ProcessorHarness.compile(
      new FromMapProcessor(),
      ProcessorHarness.source(
        "demo.Ticket",
        "package demo;\nimport io.github.eschizoid.telescope.annotations.FromMap;\n" +
          "@FromMap(required = {\"customer\"})\npublic record Ticket(String id, String note) {}\n"
      )
    );

    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(
        "@FromMap: required names 'customer', which is not a component of Ticket. Known: [id, note]"
      ),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("a required name that is not a bean property is a compile error naming it as a property")
  void anUnknownBeanNameIsRefused() {
    final var compilation = ProcessorHarness.compile(
      new FromMapProcessor(),
      ProcessorHarness.source(
        "demo.Bean",
        "package demo;\nimport io.github.eschizoid.telescope.annotations.FromMap;\n" +
          "@FromMap(required = {\"nope\"})\npublic class Bean {\n  private String id;\n  public Bean() {}\n" +
          "  public String getId() { return id; }\n  public void setId(final String id) { this.id = id; }\n}\n"
      )
    );

    assertTrue(
      compilation.hasError("required names 'nope', which is not a property of Bean"),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("a type with no required names emits no refusal at all")
  void noRequiredNamesEmitsNoRefusal() {
    final var compilation = ProcessorHarness.compile(
      new FromMapProcessor(),
      ProcessorHarness.source(
        "demo.Plain",
        "package demo;\nimport io.github.eschizoid.telescope.annotations.FromMap;\n" +
          "@FromMap\npublic record Plain(String id) {}\n"
      )
    );

    assertTrue(compilation.success(), compilation::errorMessages);
    assertFalse(compilation.generated().get("demo.PlainFromMap").contains("__refuseMissing"));
  }

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  @Test
  @DisplayName("a required name listed twice is a compile error")
  void aDuplicateNameIsRefused() {
    final var compilation = ProcessorHarness.compile(
      new FromMapProcessor(),
      ProcessorHarness.source(
        "demo.Twice",
        "package demo;\nimport io.github.eschizoid.telescope.annotations.FromMap;\n" +
          "@FromMap(required = {\"id\", \"id\"})\npublic record Twice(String id) {}\n"
      )
    );

    assertTrue(compilation.hasError("@FromMap: required names 'id' more than once"), compilation::errorMessages);
  }

  /**
   * A target whose simple name is one the binder's own code uses: the binder must still build and
   * return the target, not the JDK type of that name.
   */
  private static void assertBinderBuildsTheTarget(final String simpleName) throws ReflectiveOperationException {
    final var source = ProcessorHarness.source(
      PACKAGE + "." + simpleName,
      "package " +
        PACKAGE +
        ";\nimport io.github.eschizoid.telescope.annotations.FromMap;\n" +
        "@FromMap(required = {\"a\"})\npublic record " +
        simpleName +
        "(String a, int b) {}\n"
    );
    final var compilation = ProcessorHarness.compileFully(List.of(new FromMapProcessor()), List.of(), source);
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var target = classes.get(PACKAGE + "." + simpleName);
    final var fromMap = classes.get(PACKAGE + "." + simpleName + "FromMap").getMethod("fromMap", Map.class);

    final var built = fromMap.invoke(null, Map.of("a", "x", "b", 2));
    assertSame(target, built.getClass());
    assertEquals(simpleName + "[a=x, b=2]", built.toString());

    final var refusal = assertThrows(InvocationTargetException.class, () -> fromMap.invoke(null, Map.of()));
    assertEquals(
      simpleName + "FromMap.fromMap: the map carries no value for required key \"a\" (component 'a') of " + simpleName,
      refusal.getCause().getMessage()
    );
  }

  @Test
  @DisplayName(
    "a target named StringBuilder, StringJoiner or IllegalArgumentException is built, and refuses, as itself"
  )
  void aTargetNamedLikeTheRefusalTypesIsItself() throws ReflectiveOperationException {
    assertBinderBuildsTheTarget("StringBuilder");
    assertBinderBuildsTheTarget("StringJoiner");
    assertBinderBuildsTheTarget("IllegalArgumentException");
  }

  @Test
  @DisplayName("a target named Map or ForwardMapper is built as itself, not as the type the binder imports")
  void aTargetNamedLikeAnImportIsItself() throws ReflectiveOperationException {
    assertBinderBuildsTheTarget("Map");
    assertBinderBuildsTheTarget("ForwardMapper");
  }
}
