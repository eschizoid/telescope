package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The binder is emitted into the bean's own package, so the question is whether a constructor is
 * reachable from there rather than whether it is public. Protected and package-private both are.
 *
 * <p>Asking the narrower question refused beans the navigator accepts and the reflective path has
 * always accepted, for the same bean — and an entity that hides its no-argument constructor behind
 * an access modifier is a common enough shape to hit it.
 */
class FromMapConstructorAccessTest {

  private static Compilation compile(final JavaFileObject source) {
    return ProcessorHarness.compileFully(List.of(new FromMapProcessor()), List.of(), source);
  }

  private static JavaFileObject bean(final String ctorModifier) {
    return ProcessorHarness.source(
      "demo.Hidden",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.FromMap;
      @FromMap
      public class Hidden {
        private String name;
        %s Hidden() {}
        public String getName() { return name; }
        public void setName(final String n) { this.name = n; }
      }
      """.formatted(ctorModifier)
    );
  }

  @ParameterizedTest(name = "{0} constructor")
  @ValueSource(strings = { "public", "protected", "" })
  @DisplayName("a no-argument constructor the binder can reach is enough, whatever its access")
  void reachableConstructorIsAccepted(final String modifier) {
    // The empty string is a package-private constructor, which is the same package as the binder.
    final var compilation = compile(bean(modifier));

    assertTrue(compilation.success(), () -> "the binder sits beside it: " + compilation.errorMessages());
    assertTrue(compilation.generated().containsKey("demo.HiddenFromMap"), "and the binder is emitted");
  }

  @Test
  @DisplayName("a builder-only bean binds without any reachable constructor at all")
  void builderOnlyBeanNeedsNoConstructor() {
    // The other half of the guard. A builder supplies its own way to make the instance, so the
    // constructor question does not arise — and a change that demanded one regardless would refuse
    // the immutable shape this annotation exists to serve.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.Immutable",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.FromMap;
        @FromMap
        public final class Immutable {
          private final String name;
          private Immutable(final String name) { this.name = name; }
          public static Builder builder() { return new Builder(); }
          public String getName() { return name; }
          public static final class Builder {
            private String name;
            public Builder name(final String n) { this.name = n; return this; }
            public Immutable build() { return new Immutable(name); }
          }
        }
        """
      )
    );

    assertTrue(compilation.success(), () -> "a builder needs no constructor: " + compilation.errorMessages());
    assertTrue(compilation.generated().containsKey("demo.ImmutableFromMap"), "and the binder is emitted");
  }

  @Test
  @DisplayName("a private constructor is still refused, since nothing outside the class can call it")
  void privateConstructorIsRefused() {
    // The control. Without it a change that accepted every constructor would satisfy the rows
    // above while emitting a binder that cannot compile.
    final var compilation = compile(bean("private"));

    assertFalse(compilation.success(), "a private constructor is reachable from nowhere else");
    assertTrue(
      compilation.hasError("needs a static builder() or a no-arg constructor"),
      () -> "and the diagnostic should not promise that making it public would help: " + compilation.errorMessages()
    );
  }
}
