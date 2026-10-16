package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;

/**
 * Compiles sources with navigator processors through the full javac pipeline and fails the calling
 * test on any warning javac reports in a file a processor generated.
 *
 * <p>The harness compiles with {@code -Xlint:all}. A generated navigator, step or holder is code
 * its adopter cannot edit, so a build that compiles it with {@code -Werror} passes only when the
 * file raises nothing. The full pipeline matters: {@code -proc:only} attributes declarations and
 * not method bodies, so a warning raised inside a lens or a forwarder would go unseen.
 */
final class NavigatorCompiler {

  private NavigatorCompiler() {}

  /** {@code sources} compiled with {@code processors}, with no warning in any generated file. */
  static Compilation warningFree(final List<? extends Processor> processors, final JavaFileObject... sources) {
    final var compilation = ProcessorHarness.compileFully(processors, List.of(), sources);
    final var warnings = compilation.warningsInGenerated();
    assertTrue(warnings.isEmpty(), () -> "the generated code warns:\n  " + String.join("\n  ", warnings));
    return compilation;
  }

  /** {@code sources} compiled with {@code processor}, with no warning in any generated file. */
  static Compilation warningFree(final Processor processor, final JavaFileObject... sources) {
    return warningFree(List.of(processor), sources);
  }
}
