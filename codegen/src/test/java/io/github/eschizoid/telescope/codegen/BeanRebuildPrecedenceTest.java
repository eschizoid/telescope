package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bean offering several ways to be rebuilt is rebuilt through the one the reflective writer would
 * choose — a builder, then a constructor taking every property, then setters — so that the
 * generated holder stays an optimisation of that path rather than a second opinion.
 *
 * <p>These compile through the full pipeline. A member whose parameter does not accept the property
 * produces an error in the emitted rebuild expression, and the processing-only harness stops before
 * attributing those.
 */
class BeanRebuildPrecedenceTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BeanFocusProcessor()), List.of(), sources);
  }

  private static final String BUILDER = """
      public static Builder builder() { return new Builder(); }
      public static final class Builder {
        private %1$s v;
        public Builder v(final %1$s v) { this.v = v; return this; }
        public Widget build() { final var b = new Widget(); b.v = v; return b; }
      }
    """;

  /**
   * A bean with a no-arg constructor and a setter, plus whatever {@code extra} adds. The builder,
   * when added, always accepts the property's own declared type.
   */
  private static JavaFileObject bean(
    final String ctorModifier,
    final String propType,
    final String setterParam,
    final String extra
  ) {
    return ProcessorHarness.source(
      "demo.Widget",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.BeanFocus;
      @BeanFocus
      public class Widget {
        private %s v;
        %s Widget() {}
        public %s getV() { return v; }
        public void setV(final %s v) { /* these assert on which surface is emitted, not on effect */ }
      %s
      }
      """.formatted(propType, ctorModifier, propType, setterParam, extra.formatted(propType))
    );
  }

  /**
   * The text of one emitted member, so an assertion can name which emitter it is about. The holder
   * carries two that write through setters — the per-property lens and {@code construct} — and a
   * search of the whole file is satisfied by either, so it would hold with one of them unguarded.
   *
   * <p>The two are sliced differently because they are different shapes. A constant is emitted as a
   * single line and ends at the newline; a method ends at its own closing brace. Slicing a constant
   * as though it were a method runs past it into whatever follows, which puts the other emitter's
   * text inside the slice and restores exactly the weakness this exists to remove.
   */
  private static String constantAt(final String source, final String fragment) {
    final var start = indexOfOrFail(source, fragment);
    final var end = source.indexOf('\n', start);
    return source.substring(start, end < 0 ? source.length() : end);
  }

  private static String methodAt(final String source, final String fragment) {
    final var start = indexOfOrFail(source, fragment);
    final var end = source.indexOf("\n  }", start);
    return source.substring(start, end < 0 ? source.length() : end);
  }

  private static int indexOfOrFail(final String source, final String fragment) {
    final var at = source.indexOf(fragment);
    assertTrue(at >= 0, () -> "expected to find " + fragment + " in:\n" + source);
    return at;
  }

  private static String construct(final Compilation compilation) {
    assertTrue(compilation.success(), compilation::errorMessages);
    return methodAt(
      compilation.generated().get("demo.WidgetFieldOptics"),
      "construct(final Function<String, Object> values)"
    );
  }

  @Test
  @DisplayName("a bean with a builder and setters is rebuilt through its builder")
  void builderComesBeforeSetters() {
    final var construct = construct(compile(bean("public", "String", "String", BUILDER)));

    assertTrue(construct.contains("Widget.builder()"), () -> "the builder comes first; saw " + construct);
    assertFalse(construct.contains("new Widget()"), () -> "so the setters are not used; saw " + construct);
  }

  @Test
  @DisplayName("a setter whose parameter cannot take the property does not stop the builder")
  void mismatchedSetterParameterKeepsTheBuilder() {
    // A BigDecimal property behind a double setter. The setter exists and has the right name and
    // arity, which is all a reflective scan can see, but handing it the property's value is not
    // legal Java, so a rebuild through it would not compile.
    final var compilation = compile(bean("public", "java.math.BigDecimal", "double", BUILDER));

    assertTrue(compilation.success(), () -> "must not emit an uncompilable rebuild: " + compilation.errorMessages());
    final var holder = compilation.generated().get("demo.WidgetFieldOptics");
    assertTrue(holder.contains("builder()"), () -> "the builder is the usable surface here; saw " + holder);
  }

  @Test
  @DisplayName("a builder with no member for a property a setter writes is passed over for the setters")
  void builderMissingASetterPropertyIsPassedOver() {
    // The builder would skip `v`, which the setter writes, so taking it would lose that write.
    final var partialBuilder = """
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public Widget build() { return new Widget(); }
        }
      """;
    final var construct = construct(compile(bean("public", "String", "String", partialBuilder)));

    assertTrue(construct.contains("new Widget()"), () -> "the setters carry the bean; saw " + construct);
    assertFalse(construct.contains("builder()"), () -> "and the builder does not; saw " + construct);
  }

  @Test
  @DisplayName("a public constructor taking every property comes before the setters")
  void constructorComesBeforeSetters() {
    final var compilation = compile(bean("public", "String", "String", "  public Widget(final %s v) { this.v = v; }"));
    final var construct = construct(compilation);

    assertTrue(construct.contains("return new Widget((String)"), () -> "the constructor is called; saw " + construct);
    final var lens = constantAt(
      compilation.generated().get("demo.WidgetFieldOptics"),
      "public static final Telescope<Widget"
    );
    assertTrue(lens.contains("(p, v) -> new Widget(v)"), () -> "and the lens rebuilds through it; saw " + lens);
  }

  @Test
  @DisplayName("a builder comes before a public constructor taking every property")
  void builderComesBeforeTheConstructor() {
    final var construct = construct(
      compile(bean("public", "String", "String", BUILDER + "  public Widget(final %1$s v) { this.v = v; }"))
    );

    assertTrue(construct.contains("Widget.builder()"), () -> "the builder comes first; saw " + construct);
  }

  @Test
  @DisplayName("a primitive constructor parameter behind a boxed property takes its default for null")
  void constructorArgumentIsNullGuarded() {
    // The runtime constructor writer passes 0 for a null bound to a long parameter, so both
    // emitters owe the same, reading the property once.
    final var compilation = compile(bean("public", "Long", "Long", "  public Widget(final long v) { this.v = v; }"));
    final var holder = compilation.generated().get("demo.WidgetFieldOptics");

    final var construct = construct(compilation);
    assertTrue(construct.contains("== null ? 0L :"), () -> "construct() unboxes a null without this; saw " + construct);
    final var lens = constantAt(holder, "public static final Telescope<Widget");
    assertTrue(lens.contains("== null ? 0L :"), () -> "the lens unboxes a null without this; saw " + lens);
  }

  @Test
  @DisplayName("both setter emitters guard a primitive setter behind a boxed property")
  void primitiveSetterIsNullGuardedInBothEmitters() {
    // A Long property behind a long setter, on a bean with nothing but setters. Null unboxes and
    // throws, and the reflective writer skips the property instead of crashing, so both emitted
    // call sites have to skip it too.
    final var compilation = compile(bean("public", "Long", "long", ""));

    assertTrue(compilation.success(), () -> "a boxed property may be written: " + compilation.errorMessages());
    final var holder = compilation.generated().get("demo.WidgetFieldOptics");

    final var construct = methodAt(holder, "construct(final Function<String, Object> values)");
    assertTrue(
      construct.contains("!= null) c.setV("),
      () -> "construct() unboxes a null without this; saw " + construct
    );

    final var lens = constantAt(holder, "public static final Telescope<Widget");
    assertTrue(lens.contains("!= null) c.setV("), () -> "the lens unboxes a null without this; saw " + lens);
  }

  @Test
  @DisplayName("a no-arg constructor the navigator can reach counts, whatever its access")
  void nonPublicConstructorStillCounts() {
    // The navigator is emitted into the bean's own package, so a protected or package-private
    // constructor is callable from it. The reflective writer asks only whether one is declared, so
    // requiring public here would leave the two paths disagreeing for a bean that hides its no-arg
    // constructor, as an entity does.
    final var construct = construct(compile(bean("protected", "String", "String", "")));

    assertTrue(construct.contains("new Widget()"), () -> "setters are reachable here; saw " + construct);
  }

  @Test
  @DisplayName("a bean with only a non-public constructor and no builder is navigable now")
  void nonPublicConstructorWithoutBuilderIsAccepted() {
    // The scope this widens. Such a bean was refused outright before, because the only surface it
    // offers was judged unreachable; the navigator is emitted beside it, so it never was.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.Hidden",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        @BeanFocus
        public class Hidden {
          private String v;
          protected Hidden() {}
          public String getV() { return v; }
          public void setV(final String v) { this.v = v; }
        }
        """
      )
    );

    assertTrue(
      compilation.success(),
      () -> "a package-reachable constructor is reachable: " + compilation.errorMessages()
    );
    assertTrue(compilation.generated().containsKey("demo.HiddenFieldOptics"), "and the holder is emitted");
  }
}
