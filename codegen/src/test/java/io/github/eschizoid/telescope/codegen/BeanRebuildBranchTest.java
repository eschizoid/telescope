package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The facts a generated navigator reads to pick its rebuild, one at a time: whether a builder is
 * usable, what its members take, what the setters and a constructor write, and which properties
 * only a strategy can set. Each row is a bean the runtime writer would build the same way, and
 * asserts on the {@code construct()} the holder emits.
 */
class BeanRebuildBranchTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BeanFocusProcessor()), List.of(), sources);
  }

  private static JavaFileObject widget(final String body) {
    return ProcessorHarness.source(
      "demo.Widget",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.BeanFocus;
      @BeanFocus
      public class Widget {
      %s
      }
      """.formatted(body)
    );
  }

  private static String construct(final Compilation compilation) {
    assertTrue(compilation.success(), compilation::errorMessages);
    final var holder = compilation.generated().get("demo.WidgetFieldOptics");
    final var start = holder.indexOf("construct(final Function<String, Object> values)");
    return holder.substring(start, holder.indexOf("\n  }", start));
  }

  private static final String SETTER_V = """
      private String v;
      public Widget() {}
      public String getV() { return v; }
      public void setV(final String v) { this.v = v; }
    """;

  @Test
  @DisplayName("a builder() that returns no class is not a builder")
  void builderReturningAPrimitive() {
    final var construct = construct(compile(widget(SETTER_V + "  public static int builder() { return 0; }\n")));
    assertTrue(construct.contains("new Widget()"), construct);
  }

  @Test
  @DisplayName("a builder with no build() is not a builder")
  void builderWithoutBuild() {
    final var construct = construct(
      compile(
        widget(
          SETTER_V +
            "  public static Builder builder() { return new Builder(); }\n" +
            "  public static final class Builder { public Builder v(final String v) { return this; } }\n"
        )
      )
    );
    assertTrue(construct.contains("new Widget()"), construct);
  }

  @Test
  @DisplayName("a builder lacking a member only for a property no setter writes is kept, and skips it")
  void builderSkipsAPropertyNothingWrites() {
    final var compilation = compile(
      widget(
        SETTER_V +
          "  public String getShout() { return v == null ? null : v.toUpperCase(); }\n" +
          "  public static Builder builder() { return new Builder(); }\n" +
          "  public static final class Builder {\n" +
          "    private String v;\n" +
          "    public Builder v(final String v) { this.v = v; return this; }\n" +
          "    public Widget build() { final var w = new Widget(); w.v = v; return w; }\n" +
          "  }\n"
      )
    );
    final var construct = construct(compilation);
    assertTrue(construct.contains("Widget.builder().v("), construct);
    assertFalse(construct.contains("shout"), construct);
    final var holder = compilation.generated().get("demo.WidgetFieldOptics");
    assertFalse(holder.contains(".shout("), () -> "the lenses skip it too; saw " + holder);
  }

  @Test
  @DisplayName("a sole builder lacking a member for a computed getter is kept")
  void soleBuilderWithAComputedGetter() {
    final var construct = construct(
      compile(
        widget(
          "  private final String v;\n" +
            "  private Widget(final String v) { this.v = v; }\n" +
            "  public String getV() { return v; }\n" +
            "  public String getShout() { return v == null ? null : v.toUpperCase(); }\n" +
            "  public static Builder builder() { return new Builder(); }\n" +
            "  public static final class Builder {\n" +
            "    private String v;\n" +
            "    public Builder v(final String v) { this.v = v; return this; }\n" +
            "    public Widget build() { return new Widget(v); }\n" +
            "  }\n"
        )
      )
    );
    assertTrue(construct.contains("Widget.builder().v("), construct);
  }

  @Test
  @DisplayName("a sole builder lacking a member for an inherited field only a strategy sets is refused")
  void soleBuilderMissingAnInheritedStoredField() {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.Base",
        """
        package demo;
        public class Base {
          protected String label;
          public String getLabel() { return label; }
        }
        """
      ),
      ProcessorHarness.source(
        "demo.Widget",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        @BeanFocus
        public class Widget extends Base {
          private String v;
          private Widget() {}
          public String getV() { return v; }
          public static Builder builder() { return new Builder(); }
          public static final class Builder {
            private String v;
            public Builder v(final String v) { this.v = v; return this; }
            public Widget build() { final var w = new Widget(); w.v = v; return w; }
          }
        }
        """
      )
    );
    assertFalse(compilation.success(), "the builder would drop label");
    assertTrue(compilation.hasError("needs a static builder()"), compilation::errorMessages);
  }

  @Test
  @DisplayName("a builder member taking a primitive behind a boxed property is passed over")
  void primitiveMemberBehindABoxedProperty() {
    final var construct = construct(
      compile(
        widget(
          "  private Long v;\n" +
            "  public Widget() {}\n" +
            "  public Long getV() { return v; }\n" +
            "  public void setV(final Long v) { this.v = v; }\n" +
            "  public static Builder builder() { return new Builder(); }\n" +
            "  public static final class Builder {\n" +
            "    public Builder v(final long v) { return this; }\n" +
            "    public Widget build() { return new Widget(); }\n" +
            "  }\n"
        )
      )
    );
    assertTrue(construct.contains("new Widget()"), construct);
  }

  @Test
  @DisplayName("a builder member taking the same primitive as its property is used")
  void primitiveMemberBehindAPrimitiveProperty() {
    final var construct = construct(
      compile(
        widget(
          "  private long v;\n" +
            "  public Widget() {}\n" +
            "  public long getV() { return v; }\n" +
            "  public void setV(final long v) { this.v = v; }\n" +
            "  public static Builder builder() { return new Builder(); }\n" +
            "  public static final class Builder {\n" +
            "    private long v;\n" +
            "    public Builder v(final long v) { this.v = v; return this; }\n" +
            "    public Widget build() { final var w = new Widget(); w.v = v; return w; }\n" +
            "  }\n"
        )
      )
    );
    assertTrue(construct.contains("Widget.builder().v("), construct);
  }

  @Test
  @DisplayName("two constructors of the property count name no constructor, as at runtime")
  void twoConstructorsOfTheSameArity() {
    final var construct = construct(
      compile(
        widget(
          SETTER_V +
            "  public Widget(final String v, final int ignored) { this.v = v; }\n" +
            "  public Widget(final int ignored, final String v) { this.v = v; }\n" +
            "  public int getIgnored() { return 0; }\n" +
            "  public void setIgnored(final int ignored) {}\n"
        )
      )
    );
    assertTrue(construct.contains("new Widget()"), construct);
  }

  @Test
  @DisplayName("a setter rebuild with no setter for a property is refused, naming it")
  void settersMissingOne() {
    final var compilation = compile(widget(SETTER_V + "  public String getOther() { return null; }\n"));
    assertFalse(compilation.success());
    assertTrue(compilation.hasError("no setter for property 'other'"), compilation::errorMessages);
  }
}
