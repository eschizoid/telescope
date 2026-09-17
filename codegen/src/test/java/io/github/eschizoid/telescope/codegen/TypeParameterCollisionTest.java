package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A navigator declares type variables of its own — one for the root it navigates from, and one each
 * on the effectful-update and composition forwarders. A focused type whose simple name happens to
 * match one of them is shadowed at every use, and the emitted signatures name one type where two
 * were meant.
 *
 * <p>The trigger is an ordinary class name rather than an exotic one, and the failure lands as a
 * javac error inside a file the author never wrote. These compile through the full pipeline,
 * because the collision is in signatures the processing-only harness completes but the emitted
 * bodies referencing them are not what fails — the signature itself is.
 */
class TypeParameterCollisionTest {

  private static Compilation compile(final Processor processor, final JavaFileObject source) {
    return ProcessorHarness.compileFully(List.of(processor), List.of(), source);
  }

  @ParameterizedTest(name = "record named {0}")
  @ValueSource(strings = { "B", "E", "R", "S", "Widget" })
  @DisplayName("a record navigates whatever its name is, including the navigator's own variables")
  void recordNameNeverCollides(final String name) {
    // B, E and R are the variables a navigator declares today. S and Widget are controls: they were
    // never at risk, so a change that qualified every type reference rather than the colliding ones
    // would pass the first three and leave these reading worse for no reason.
    final var compilation = compile(
      new FocusProcessor(),
      ProcessorHarness.source(
        "demo." + name,
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Focus;
        @Focus public record %s(String name, int age) {}
        """.formatted(name)
      )
    );

    assertTrue(compilation.success(), () -> "a record may be called " + name + ": " + compilation.errorMessages());
    assertTrue(compilation.generated().containsKey("demo." + name + "Telescope"), "the navigator keeps its own name");
    assertTrue(compilation.generated().containsKey("demo." + name + "FieldOptics"), "and so does the holder");
  }

  @ParameterizedTest(name = "bean named {0}")
  @ValueSource(strings = { "B", "E", "R", "Widget" })
  @DisplayName("a bean navigates whatever its name is, through the same forwarders")
  void beanNameNeverCollides(final String name) {
    // The bean flavour shares the forwarder block, so it shares the collision. It reaches it by a
    // different route, which is why asserting it separately is not redundant.
    final var compilation = compile(
      new BeanFocusProcessor(),
      ProcessorHarness.source(
        "demo." + name,
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        @BeanFocus public class %s {
          private String name;
          public %s() {}
          public String getName() { return name; }
          public void setName(final String n) { this.name = n; }
        }
        """.formatted(name, name)
      )
    );

    assertTrue(compilation.success(), () -> "a bean may be called " + name + ": " + compilation.errorMessages());
    assertTrue(compilation.generated().containsKey("demo." + name + "Telescope"), "the navigator keeps its own name");
  }
}
