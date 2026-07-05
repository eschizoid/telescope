package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.introspection.OpticNode.Focus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a codegen-generated {@code <X>Telescope} navigator answers {@code explain()} /
 * {@code trace()} — the processors emit a {@code .hop("component")} after each lens composition, so
 * the generated path records the same introspection trail a hand-written {@code field(...)} chain
 * would. Uses the generated {@code PersonTelescope} from the {@code @Focus Person} / {@code
 * Address} fixtures.
 */
class GeneratedNavigatorIntrospectionTest {

  @Test
  @DisplayName("a generated navigator's explain() records each field hop, including nested ones")
  void generatedExplain() {
    final var report = PersonTelescope.of().address().city().explain();
    assertEquals(List.of(new Focus("address"), new Focus("city")), report.nodes());
  }

  @Test
  @DisplayName("a single-field generated navigator explains one hop")
  void generatedSingleHop() {
    assertEquals(List.of(new Focus("name")), PersonTelescope.of().name().explain().nodes());
  }

  @Test
  @DisplayName("a generated navigator's trace() runs the path against a value")
  void generatedTrace() {
    final var person = new Person("Ada", 30, new Address("Paris", "75001"));
    final var trace = PersonTelescope.of().address().city().trace(person);
    assertTrue(trace.toString().contains("\"Paris\""), trace::toString);
  }
}
