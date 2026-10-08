package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A navigator written before the final round names a child's navigator only when that navigator is
 * also written before it. A {@code @BeanFocus} child carrying a Lombok bean annotation is written
 * early by telescope-lombok's processor, and in the final round by the {@code @BeanFocus} processor
 * when telescope-lombok's does not run; the parent's hop to it descends in the first case and ends
 * in the second.
 *
 * <p>Each source ends in a class naming the parents' hops by their type — to the child, and to an
 * element of a list of children — so a hop of the other shape, or a navigator written too late to
 * be named, fails the compilation.
 */
class FinalRoundChildNavigatorTest {

  private static String source(final String hopType) {
    return """
    package demo;
    import io.github.eschizoid.telescope.Telescope;
    import io.github.eschizoid.telescope.annotations.BeanFocus;
    import java.util.List;
    import lombok.Data;
    @BeanFocus
    public class Holder {
      private Part part;
      private List<Part> parts;
      public Holder() {}
      public Part getPart() { return part; }
      public void setPart(final Part part) { this.part = part; }
      public List<Part> getParts() { return parts; }
      public void setParts(final List<Part> parts) { this.parts = parts; }
    }
    @BeanFocus
    class Shelf {
      private List<Part> parts;
      public Shelf() {}
      public List<Part> getParts() { return parts; }
      public void setParts(final List<Part> parts) { this.parts = parts; }
    }
    @BeanFocus
    @Data
    class Part {
      private String name;
    }
    class Use {
      final %1$s hop = HolderTelescope.of().part();
      final %1$s each = HolderTelescope.of().parts().each();
      final %2$s shelfEach = ShelfTelescope.of().parts().each();
    }
    """.formatted(hopType, hopType.replace("Holder", "Shelf"));
  }

  @Test
  @DisplayName("with telescope-lombok on the path, the hop descends into the child's navigator")
  void descendsWhenTheLombokProcessorRuns(@TempDir final Path dir) throws IOException {
    final var generated = LombokLastCompiler.compile(dir, "Holder", source("PartTelescope<Holder>"), true);

    assertTrue(Files.exists(generated.resolve("demo/PartTelescope.java")));
  }

  @Test
  @DisplayName("with the @BeanFocus processor running before telescope-lombok's, the hop still descends")
  void descendsWhenTheBeanFocusProcessorRunsFirst(@TempDir final Path dir) throws IOException {
    final var generated = LombokLastCompiler.compile(
      dir,
      "Holder",
      source("PartTelescope<Holder>"),
      true,
      List.of(
        "-processor",
        "io.github.eschizoid.telescope.codegen.BeanFocusProcessor," +
          "io.github.eschizoid.telescope.codegen.lombok.LombokFocusProcessor," +
          "lombok.launch.AnnotationProcessorHider$AnnotationProcessor," +
          "lombok.launch.AnnotationProcessorHider$ClaimingProcessor"
      )
    );

    assertTrue(Files.exists(generated.resolve("demo/PartTelescope.java")));
  }

  @Test
  @DisplayName("without telescope-lombok, the hop ends at the child, whose navigator is written in the final round")
  void endsWithoutTheLombokProcessor(@TempDir final Path dir) throws IOException {
    final var generated = LombokLastCompiler.compile(dir, "Holder", source("Telescope<Holder, Part>"), false);

    assertTrue(Files.exists(generated.resolve("demo/PartTelescope.java")), "the @BeanFocus processor writes it");
  }

  @Test
  @DisplayName("with an explicit processor list that leaves telescope-lombok out, the hop ends at the child")
  void endsWhenAProcessorListLeavesItOut(@TempDir final Path dir) throws IOException {
    final var generated = LombokLastCompiler.compile(
      dir,
      "Holder",
      source("Telescope<Holder, Part>"),
      true,
      List.of(
        "-processor",
        "lombok.launch.AnnotationProcessorHider$AnnotationProcessor," +
          "lombok.launch.AnnotationProcessorHider$ClaimingProcessor," +
          "io.github.eschizoid.telescope.codegen.BeanFocusProcessor"
      )
    );

    assertTrue(Files.exists(generated.resolve("demo/PartTelescope.java")), "the @BeanFocus processor writes it");
  }

  @Test
  @DisplayName("a compilation without telescope-lombok writes no navigator for a class carrying only @Data")
  void theLombokProcessorIsAbsentWhenDropped(@TempDir final Path dir) throws IOException {
    final var generated = LombokLastCompiler.compile(
      dir,
      "Plain",
      """
      package demo;
      @lombok.Data
      public class Plain {
        private String name;
      }
      """,
      false
    );

    assertFalse(Files.exists(generated.resolve("demo/PlainTelescope.java")));
  }
}
