package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * When {@code LombokFocusProcessor} counts a target ready, one Lombok annotation at a time. Lombok
 * itself does not run in this harness, so each class writes by hand the members Lombok would add,
 * or leaves one out. A ready target is emitted in the first round; one that never becomes ready is
 * emitted in the final round, which javac reports as a file created in the last round.
 */
class LombokReadinessBranchTest {

  private static final String ACCESSORS = """
      public Widget() {}
      public String getA() { return a; }
      public void setA(final String a) { this.a = a; }
    """;

  /** A hand-written builder over {@code a} and {@code extra}, so every target has a strategy. */
  private static String builder(final String extra, final String type) {
    return (
      "  public static Builder builder() { return new Builder(); }\n" +
      "  public static final class Builder {\n" +
      "    public Builder a(final String a) { return this; }\n" +
      (extra.isEmpty() ? "" : "    public Builder " + extra + "(final " + type + " v) { return this; }\n") +
      "    public Widget build() { return new Widget(); }\n" +
      "  }\n"
    );
  }

  static List<Arguments> shapes() {
    return List.of(
      // @Builder: ready once builder() is there.
      Arguments.of("@Builder without builder()", "@lombok.Builder", "  private String a;\n" + ACCESSORS, false),
      Arguments.of(
        "@Builder with builder()",
        "@lombok.Builder",
        "  private String a;\n" +
          ACCESSORS +
          "  public static Builder builder() { return new Builder(); }\n" +
          "  public static final class Builder {\n" +
          "    public Builder a(final String a) { return this; }\n" +
          "    public Widget build() { return new Widget(); }\n" +
          "  }\n",
        true
      ),
      // @AllArgsConstructor and @Value: ready once the constructor over the unset fields is
      // there.
      Arguments.of(
        "@AllArgsConstructor without it",
        "@lombok.Data @lombok.AllArgsConstructor",
        "  private String a;\n" + ACCESSORS,
        false
      ),
      Arguments.of(
        "@AllArgsConstructor with it",
        "@lombok.Data @lombok.AllArgsConstructor",
        "  private String a;\n" + ACCESSORS + "  public Widget(final String a) { this.a = a; }\n",
        true
      ),
      Arguments.of(
        "@AllArgsConstructor leaving out an initialised field",
        "@lombok.Data @lombok.AllArgsConstructor",
        "  private String a;\n  private final String k = \"K\";\n  public String getK() { return k; }\n" +
          ACCESSORS +
          "  public Widget(final String a) { this.a = a; }\n" +
          builder("", ""),
        true
      ),
      Arguments.of("@Value without it", "@lombok.Value", "  private String a;\n" + ACCESSORS, false),
      // @RequiredArgsConstructor: counts the unset finals; none asks for nothing.
      Arguments.of(
        "@RequiredArgsConstructor without it",
        "@lombok.Data @lombok.RequiredArgsConstructor",
        "  private final String b;\n  public String getB() { return b; }\n  private String a;\n" +
          "  private Widget() { this.b = null; }\n" +
          "  public String getA() { return a; }\n  public void setA(final String a) { this.a = a; }\n" +
          builder("b", "String"),
        false
      ),
      Arguments.of(
        "@RequiredArgsConstructor with it",
        "@lombok.Data @lombok.RequiredArgsConstructor",
        "  private final String b;\n  public String getB() { return b; }\n  private String a;\n" +
          "  private Widget() { this.b = null; }\n  Widget(final String b) { this.b = b; }\n" +
          "  public String getA() { return a; }\n  public void setA(final String a) { this.a = a; }\n" +
          builder("b", "String"),
        true
      ),
      Arguments.of(
        "@RequiredArgsConstructor with no final",
        "@lombok.Data @lombok.RequiredArgsConstructor",
        "  private String a;\n" + ACCESSORS,
        true
      ),
      // @Data / @Setter: the setter under Lombok's own name.
      Arguments.of(
        "@Data with setActive for isActive",
        "@lombok.Data",
        "  private String a;\n  private boolean isActive;\n" +
          ACCESSORS +
          "  public boolean isActive() { return isActive; }\n  public void setActive(final boolean v) { isActive = v; }\n",
        true
      ),
      Arguments.of(
        "@Data with only setIsActive",
        "@lombok.Data",
        "  private String a;\n  private boolean isActive;\n" +
          ACCESSORS +
          "  public boolean isActive() { return isActive; }\n  public void setIsActive(final boolean v) { isActive = v; }\n" +
          builder("active", "boolean"),
        false
      ),
      Arguments.of(
        "@Data with a field whose setter is suppressed",
        "@lombok.Data",
        "  private String a;\n  @lombok.Setter(lombok.AccessLevel.NONE) private String hidden;\n" + ACCESSORS,
        true
      ),
      Arguments.of(
        "@Data with setters suppressed on the class",
        "@lombok.Data @lombok.Setter(lombok.AccessLevel.NONE)",
        "  private String a;\n  private String b;\n" + ACCESSORS,
        true
      ),
      Arguments.of(
        "@Data with @Accessors on the class",
        "@lombok.Data @lombok.experimental.Accessors(fluent = true)",
        "  private String a;\n  private String b;\n" + ACCESSORS,
        true
      ),
      Arguments.of(
        "@Data with @Accessors on a field",
        "@lombok.Data",
        "  private String a;\n  @lombok.experimental.Accessors(fluent = true) private String b;\n" + ACCESSORS,
        true
      ),
      Arguments.of(
        "@Data skips a final field",
        "@lombok.Data",
        "  private String a;\n  private final String f = \"F\";\n  public String getF() { return f; }\n" +
          ACCESSORS +
          builder("", ""),
        true
      )
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("shapes")
  void readiness(final String name, final String annotations, final String body, final boolean ready) {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new LombokFocusProcessor()),
      List.of(),
      ProcessorHarness.source(
        "demo.Widget",
        "package demo;\n" + annotations + "\npublic class Widget {\n" + body + "}\n"
      )
    );
    final var lastRound = compilation.errorMessages().contains("demo.WidgetTelescope' created in the last round");
    final var emitted = compilation.generated().containsKey("demo.WidgetTelescope");
    assertTrue(emitted, () -> name + ": no navigator; " + compilation.errorMessages());
    assertTrue(
      lastRound != ready,
      () -> name + ": expected " + (ready ? "first" : "last") + " round; " + compilation.errorMessages()
    );
  }
}
