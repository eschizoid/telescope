package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code @BeanFocus} navigator ends its hop at a child whose navigator is written only in the
 * final round, since a navigator written earlier cannot name it. Without telescope-lombok's
 * processor, as in this module's tests, a {@code @BeanFocus} child carrying a Lombok annotation is
 * written by the {@code @BeanFocus} processor in the final round. The Lombok annotations are
 * stand-ins declared in source: the processors recognise Lombok by the annotation's name alone.
 */
class FinalRoundChildHopTest {

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

  private static JavaFileObject bean(final String name, final String annotations) {
    return ProcessorHarness.source(
      "demo." + name,
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.BeanFocus;
      @BeanFocus
      %s
      public class %s {
        private String v;
        public %s() {}
        public String getV() { return v; }
        public void setV(final String v) { this.v = v; }
      }
      """.formatted(annotations, name, name)
    );
  }

  /** A plain {@code @BeanFocus} holder of {@code Part}, and a class naming its hop by type. */
  private static Compilation compileHolderOf(final String partAnnotations, final String hopType) {
    return ProcessorHarness.compileFully(
      List.of(new BeanFocusProcessor()),
      List.of(),
      LOMBOK_GETTER,
      LOMBOK_DATA,
      bean("Part", partAnnotations),
      ProcessorHarness.source(
        "demo.Holder",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        @BeanFocus
        public class Holder {
          private Part part;
          public Holder() {}
          public Part getPart() { return part; }
          public void setPart(final Part part) { this.part = part; }
        }
        """
      ),
      ProcessorHarness.source(
        "demo.Use",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        class Use {
          final %s hop = HolderTelescope.of().part();
        }
        """.formatted(hopType)
      )
    );
  }

  @Test
  @DisplayName("a hop to a child carrying @Getter ends at the child")
  void hopToAGetterChildEnds() {
    final var compilation = compileHolderOf("@lombok.Getter", "Telescope<Holder, Part>");
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(
      compilation.errorMessages().contains("demo.PartTelescope' created in the last round"),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("a hop to a child carrying @Data ends at the child when telescope-lombok is not on the path")
  void hopToADataChildEnds() {
    final var compilation = compileHolderOf("@lombok.Data", "Telescope<Holder, Part>");
    assertTrue(compilation.success(), compilation::errorMessages);
    // With nothing else to take the child, the holder is written at once, not held back a round.
    assertFalse(
      compilation.errorMessages().contains("demo.HolderTelescope' created in the last round"),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("a hop to a child without a Lombok annotation still descends")
  void hopToAPlainChildDescends() {
    final var compilation = compileHolderOf("", "PartTelescope<Holder>");
    assertTrue(compilation.success(), compilation::errorMessages);
  }

  @Test
  @DisplayName("a navigator written in the final round descends into a child written in that round")
  void finalRoundHolderDescendsIntoAFinalRoundChild() {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BeanFocusProcessor()),
      List.of(),
      LOMBOK_GETTER,
      bean("Part", "@lombok.Getter"),
      ProcessorHarness.source(
        "demo.Holder",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        @BeanFocus
        @lombok.Getter
        public class Holder {
          private Part part;
          public Holder() {}
          public Part getPart() { return part; }
          public void setPart(final Part part) { this.part = part; }
        }
        """
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var hop = compilation
      .generated()
      .get("demo.HolderTelescope")
      .lines()
      .filter(line -> line.contains(" part()"))
      .toList();
    assertEquals(List.of("  public demo.PartTelescope<R> part() {"), hop);
  }

  /**
   * Writes the navigator of {@code demo.Taken} only, as telescope-lombok's processor writes one.
   */
  @SupportedAnnotationTypes("lombok.Data")
  @SupportedSourceVersion(SourceVersion.RELEASE_21)
  static final class TakesOneProcessor extends AbstractTelescopeProcessor {

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
      final var taken = processingEnv.getElementUtils().getTypeElement("demo.Taken");
      if (taken != null && roundEnv.getRootElements().contains(taken)) {
        markWrittenByLombok(taken);
        emitBeanNavigator(taken, "@Data", navigableBeanAnnotations());
      }
      return false;
    }
  }

  @Test
  @DisplayName("the @BeanFocus processor yields only the classes the Lombok processor took")
  void yieldIsPerClass() {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new TakesOneProcessor(), new BeanFocusProcessor()),
      List.of(),
      LOMBOK_DATA,
      bean("Taken", "@lombok.Data"),
      bean("Left", "@lombok.Data")
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(compilation.generated().containsKey("demo.TakenTelescope"), compilation::errorMessages);
    assertFalse(
      compilation.errorMessages().contains("demo.TakenTelescope' created in the last round"),
      compilation::errorMessages
    );
    assertTrue(
      compilation.errorMessages().contains("demo.LeftTelescope' created in the last round"),
      compilation::errorMessages
    );
  }
}
