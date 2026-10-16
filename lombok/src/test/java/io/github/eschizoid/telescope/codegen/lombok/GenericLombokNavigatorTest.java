package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The navigator, steps and holder telescope-lombok writes for a generic Lombok bean compile with no
 * warning under {@code -Xlint:all}. They are code the bean's author cannot edit, so a build
 * compiling them with {@code -Werror} passes only when they raise nothing.
 *
 * <p>Runs real javac with Lombok on the processor path, since Lombok's hooks do not install in the
 * in-memory harness.
 */
class GenericLombokNavigatorTest {

  @Test
  @DisplayName("a generic @Data bean's navigator and holder compile with no warning")
  void aGenericDataBeanCompilesWithNoWarning(@TempDir final Path dir) throws IOException {
    final var warnings = LombokLastCompiler.generatedWarnings(
      dir,
      "GenericData",
      """
      package demo;
      @lombok.Data
      public class GenericData<X> {
        private String name;
        private java.util.List<String> tags;
        private int count;
      }
      """
    );

    assertTrue(Files.exists(dir.resolve("generated/demo/GenericDataTelescope.java")), "no navigator generated");
    assertTrue(warnings.isEmpty(), () -> "the generated code warns:\n  " + String.join("\n  ", warnings));
  }

  @Test
  @DisplayName("a generic @Value @Builder bean's navigator and holder compile with no warning")
  void aGenericBuilderBeanCompilesWithNoWarning(@TempDir final Path dir) throws IOException {
    final var warnings = LombokLastCompiler.generatedWarnings(
      dir,
      "GenericBuilt",
      """
      package demo;
      @lombok.Value
      @lombok.Builder
      public class GenericBuilt<X> {
        String name;
        java.util.List<String> tags;
      }
      """
    );

    assertTrue(Files.exists(dir.resolve("generated/demo/GenericBuiltTelescope.java")), "no navigator generated");
    assertTrue(warnings.isEmpty(), () -> "the generated code warns:\n  " + String.join("\n  ", warnings));
  }
}
