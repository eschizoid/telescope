package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A navigator's bridge hop descends into the target's navigator only when a navigator is written
 * for the target in time to be named from the source's. Without telescope-lombok's processor, as in
 * this module's tests, a target carrying a Lombok annotation gets its navigator from the
 * {@code @BeanFocus} processor in the final round when it carries {@code @BeanFocus}, and no
 * navigator otherwise. The Lombok annotations are stand-ins declared in source: the processors
 * recognise Lombok by the annotation's name alone.
 *
 * <p>Each compilation ends in a class naming the hop by its type, so a hop of the other shape, or
 * one naming a navigator written too late or never, fails the compilation.
 */
class BridgeHopInTimeTest {

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

  private static final JavaFileObject BEAN_SOURCE = ProcessorHarness.source(
    "demo.Src",
    """
    package demo;
    import io.github.eschizoid.telescope.annotations.BeanFocus;
    import io.github.eschizoid.telescope.annotations.Bridge;
    @BeanFocus
    @Bridge(Tgt.class)
    public class Src {
      private String name;
      public Src() {}
      public String getName() { return name; }
      public void setName(final String name) { this.name = name; }
    }
    """
  );

  private static final JavaFileObject RECORD_SOURCE = ProcessorHarness.source(
    "demo.Src",
    """
    package demo;
    import io.github.eschizoid.telescope.annotations.Bridge;
    import io.github.eschizoid.telescope.annotations.Focus;
    @Focus
    @Bridge(Tgt.class)
    public record Src(String name) {}
    """
  );

  private static JavaFileObject target(final String annotations) {
    return ProcessorHarness.source(
      "demo.Tgt",
      """
      package demo;
      %s
      public class Tgt {
        private String name;
        public Tgt() {}
        public String getName() { return name; }
        public void setName(final String name) { this.name = name; }
      }
      """.formatted(annotations)
    );
  }

  private static JavaFileObject use(final String hopType) {
    return ProcessorHarness.source(
      "demo.Use",
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      class Use {
        final %s hop = SrcTelescope.of().asTgt();
      }
      """.formatted(hopType)
    );
  }

  private static Compilation compile(final JavaFileObject source, final String targetAnnotations, final String hop) {
    return ProcessorHarness.compileFully(
      List.of(new FocusProcessor(), new BeanFocusProcessor(), new BridgeProcessor()),
      List.of(),
      LOMBOK_GETTER,
      LOMBOK_DATA,
      source,
      target(targetAnnotations),
      use(hop)
    );
  }

  @Test
  @DisplayName("a bridge hop to a @BeanFocus target carrying @Getter ends at the target")
  void hopToAGetterTargetEnds() {
    final var compilation = compile(
      BEAN_SOURCE,
      "@io.github.eschizoid.telescope.annotations.BeanFocus @lombok.Getter",
      "Telescope<Src, Tgt>"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
  }

  @Test
  @DisplayName("a bridge hop to a @BeanFocus target carrying @Data ends at the target without telescope-lombok")
  void hopToABeanFocusDataTargetEnds() {
    final var compilation = compile(
      BEAN_SOURCE,
      "@io.github.eschizoid.telescope.annotations.BeanFocus @lombok.Data",
      "Telescope<Src, Tgt>"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
  }

  @Test
  @DisplayName("a bridge hop to a target carrying only @Data ends at the target without telescope-lombok")
  void hopToADataOnlyTargetEnds() {
    final var compilation = compile(BEAN_SOURCE, "@lombok.Data", "Telescope<Src, Tgt>");
    assertTrue(compilation.success(), compilation::errorMessages);
  }

  @Test
  @DisplayName("a record's bridge hop to a @BeanFocus target carrying @Getter ends at the target")
  void recordHopToAGetterTargetEnds() {
    final var compilation = compile(
      RECORD_SOURCE,
      "@io.github.eschizoid.telescope.annotations.BeanFocus @lombok.Getter",
      "Telescope<Src, Tgt>"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
  }

  @Test
  @DisplayName("a bridge hop to a @BeanFocus target without a Lombok annotation still descends")
  void hopToAPlainTargetDescends() {
    final var compilation = compile(
      BEAN_SOURCE,
      "@io.github.eschizoid.telescope.annotations.BeanFocus",
      "TgtTelescope<Src>"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
  }
}
