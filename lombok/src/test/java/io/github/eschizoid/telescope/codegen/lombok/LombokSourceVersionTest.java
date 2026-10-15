package io.github.eschizoid.telescope.codegen.lombok;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import java.util.List;
import java.util.Locale;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * javac reports a processor whose supported source version is older than the compilation's source
 * level with a warning that carries no source file, so {@code -Werror} fails a build on it. javac
 * only consults the processor once a source carries one of its Lombok triggers.
 */
class LombokSourceVersionTest {

  @Test
  @DisplayName("LombokFocusProcessor claims the newest source level the compiler supports, so javac does not warn")
  void noSourceVersionWarningAtTheNewestLevel() {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new LombokFocusProcessor()),
      List.of("--release", Integer.toString(SourceVersion.latestSupported().runtimeVersion().feature()), "-Xlint:all"),
      source(
        "demo.DataPojo",
        """
        package demo;
        import lombok.Data;
        @Data
        public class DataPojo {
          private String email;
          public DataPojo() {}
          public String getEmail() { return email; }
          public void setEmail(final String email) { this.email = email; }
        }
        """
      )
    );
    final var sourceless = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
      .filter(d -> d.getSource() == null)
      .map(d -> d.getMessage(Locale.ROOT))
      .toList();
    assertTrue(sourceless.isEmpty(), () -> "warnings with no source file: " + sourceless);
  }
}
