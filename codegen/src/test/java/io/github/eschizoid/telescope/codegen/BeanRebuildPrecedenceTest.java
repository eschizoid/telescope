package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bean offering both a builder and setters is rebuilt through whichever surface the reflective
 * writer would choose, so that the generated holder stays an optimisation of that path rather than
 * a second opinion. Preferring setters is only correct where they can actually take every value the
 * property can hold, which is a question about the setter's parameter type and not about its name.
 *
 * <p>These compile through the full pipeline. A setter whose parameter does not accept the property
 * produces an error in the emitted rebuild expression, and the processing-only harness stops before
 * attributing those.
 */
class BeanRebuildPrecedenceTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BeanFocusProcessor()), List.of(), sources);
  }

  /** A bean with both surfaces. The builder always accepts the property's own declared type. */
  private static JavaFileObject bean(final String ctorModifier, final String propType, final String setterParam) {
    return ProcessorHarness.source(
      "demo.Widget",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.BeanFocus;
      @BeanFocus
      public class Widget {
        private %s v;
        %s Widget() {}
        public static Builder builder() { return new Builder(); }
        public %s getV() { return v; }
        public void setV(final %s v) { /* these assert on which surface is emitted, not on effect */ }
        public static final class Builder {
          private %s v;
          public Builder v(final %s v) { this.v = v; return this; }
          public Widget build() { final var b = new Widget(); b.v = v; return b; }
        }
      }
      """.formatted(propType, ctorModifier, propType, setterParam, propType, propType)
    );
  }

  @Test
  @DisplayName("a setter whose parameter cannot take the property leaves the rebuild on the builder")
  void mismatchedSetterParameterKeepsTheBuilder() {
    // A BigDecimal property behind a double setter. The setter exists and has the right name and
    // arity, which is all a reflective scan can see, but handing it the property's value is not
    // legal Java — so preferring it here emits a rebuild that does not compile.
    final var compilation = compile(bean("public", "java.math.BigDecimal", "double"));

    assertTrue(compilation.success(), () -> "must not emit an uncompilable rebuild: " + compilation.errorMessages());
    final var holder = compilation.generated().get("demo.WidgetFieldOptics");
    assertTrue(holder.contains("builder()"), () -> "the builder is the only usable surface here; saw " + holder);
  }

  /**
   * The body of the named method within {@code source}, so an assertion can name which emitter it
   * is about. The holder carries two that write through setters — the per-property lens and {@code
   * construct} — and a search of the whole file is satisfied by either, so it would hold with one
   * of them unguarded.
   */
  private static String bodyOf(final String source, final String signatureFragment) {
    final var start = source.indexOf(signatureFragment);
    assertTrue(start >= 0, () -> "expected to find " + signatureFragment + " in:\n" + source);
    final var end = source.indexOf("\n  }", start);
    return source.substring(start, end < 0 ? source.length() : end);
  }

  @Test
  @DisplayName("both setter emitters guard a primitive setter behind a boxed property")
  void primitiveSetterIsNullGuardedInBothEmitters() {
    // A Long property behind a long setter. The setter takes every non-null value, so setters are
    // still preferred — but null unboxes and throws, and the reflective writer skips the property
    // instead of crashing, so both emitted call sites have to skip it too.
    final var compilation = compile(bean("public", "Long", "long"));

    assertTrue(compilation.success(), () -> "a boxed property may be written: " + compilation.errorMessages());
    final var holder = compilation.generated().get("demo.WidgetFieldOptics");

    final var construct = bodyOf(holder, "construct(final Function<String, Object> values)");
    assertTrue(
      construct.contains("!= null) c.setV("),
      () -> "construct() unboxes a null without this; saw " + construct
    );

    final var lens = bodyOf(holder, "public static final Telescope<Widget");
    assertTrue(lens.contains("!= null) c.setV("), () -> "the lens unboxes a null without this; saw " + lens);
  }

  @Test
  @DisplayName("a no-arg constructor the navigator can reach counts, whatever its access")
  void nonPublicConstructorStillCounts() {
    // The navigator is emitted into the bean's own package, so a protected or package-private
    // constructor is callable from it. The reflective writer asks only whether one is declared, so
    // requiring public here would leave the two paths disagreeing for the commonest bean that has
    // both surfaces — an entity that hides its no-arg constructor.
    final var compilation = compile(bean("protected", "String", "String"));

    assertTrue(compilation.success(), () -> compilation.errorMessages());
    final var holder = compilation.generated().get("demo.WidgetFieldOptics");
    assertTrue(holder.contains("new Widget()"), () -> "setters are reachable here; saw " + holder);
    assertFalse(holder.contains("builder()"), () -> "so the builder should not be chosen; saw " + holder);
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

  @Test
  @DisplayName("a matching setter on a public constructor is still preferred, as before")
  void theOrdinaryCaseStillPrefersSetters() {
    // The control. Without it, a change that pushed every bean back onto the builder would satisfy
    // the first test and break the parity this precedence exists to provide.
    final var compilation = compile(bean("public", "String", "String"));

    assertTrue(compilation.success(), () -> compilation.errorMessages());
    final var holder = compilation.generated().get("demo.WidgetFieldOptics");
    assertTrue(holder.contains("new Widget()"), () -> "setters cover this bean; saw " + holder);
  }
}
