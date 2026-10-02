package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code @BeanFocus} target carrying a Lombok annotation is emitted in the final round, after
 * Lombok has finished, and one without is emitted at once. javac reports a file created in the last
 * round, which is how these tell the two apart. The Lombok annotation here is a stand-in declared
 * in source: the processor recognises Lombok by the annotation's name alone.
 */
class BeanFocusLombokTriggerTest {

  private static final JavaFileObject LOMBOK_GETTER = ProcessorHarness.source(
    "lombok.Getter",
    """
    package lombok;
    public @interface Getter {}
    """
  );

  private static final JavaFileObject LOMBOK_DATA = ProcessorHarness.source(
    "lombok.Data",
    """
    package lombok;
    public @interface Data {}
    """
  );

  private static Compilation compile(final String annotation) {
    return ProcessorHarness.compileFully(
      List.of(new BeanFocusProcessor()),
      List.of(),
      LOMBOK_GETTER,
      LOMBOK_DATA,
      ProcessorHarness.source(
        "demo.Widget",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        @BeanFocus
        %s
        public class Widget {
          private String v;
          public Widget() {}
          public String getV() { return v; }
          public void setV(final String v) { this.v = v; }
        }
        """.formatted(annotation)
      )
    );
  }

  @Test
  @DisplayName("a target carrying a Lombok annotation is emitted in the final round")
  void lombokTargetIsDeferred() {
    final var compilation = compile("@lombok.Getter");
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(compilation.generated().containsKey("demo.WidgetTelescope"));
    assertTrue(
      compilation.errorMessages().contains("demo.WidgetTelescope' created in the last round"),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("a target carrying @Data is still emitted here when telescope-lombok is not on the processor path")
  void lombokBeanTriggerWithoutTheLombokProcessor() {
    // This module's tests have no telescope-lombok, so nothing else would write the navigator; the
    // yield to that processor applies only where it runs.
    final var compilation = compile("@lombok.Data");
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(compilation.generated().containsKey("demo.WidgetTelescope"), compilation::errorMessages);
    assertTrue(compilation.generated().containsKey("demo.WidgetFieldOptics"), compilation::errorMessages);
  }

  @Test
  @DisplayName("a target without one is emitted at once")
  void plainTargetIsNot() {
    final var compilation = compile("");
    assertTrue(compilation.success(), compilation::errorMessages);
    assertFalse(compilation.errorMessages().contains("created in the last round"), compilation::errorMessages);
  }
}
