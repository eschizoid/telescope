package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.lombok.fixtures.ChainNode;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.ChainNodeTelescope;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A property a Lombok bean inherits from a generic superclass, typed by that superclass's variable.
 * The navigator types it as the subclass fixes the variable, or refuses the class by name when the
 * subclass passes a variable of its own through.
 */
class InheritedGenericPropertyTest {

  @Test
  @DisplayName("an inherited self-bound property navigates into the subclass's own navigator")
  void anInheritedSelfBoundPropertyNavigatesIntoTheSubclass() {
    final var head = new ChainNode();
    final var tail = new ChainNode();
    head.setLabel("head");
    tail.setLabel("a");
    head.setNext(tail);

    final var written = ChainNodeTelescope.of().next().label().set(head, "b");

    assertEquals("b", written.getNext().getLabel());
    assertEquals("head", written.getLabel());
    assertNull(written.getNext().getNext(), "the rebuilt tail keeps its own next");
  }

  @Test
  @DisplayName("a subclass that passes its own variable through is refused by name")
  void aSubclassPassingItsOwnVariableThroughIsRefusedByName(@TempDir final Path dir) throws IOException {
    final var errors = LombokLastCompiler.errors(
      dir,
      "Page",
      """
      package demo;
      @lombok.Data
      @lombok.NoArgsConstructor
      @lombok.EqualsAndHashCode(callSuper = false)
      public class Page<X> extends PageBase<X> {
        private String title;
      }
      abstract class PageBase<T> {
        private T first;
        public T getFirst() { return first; }
        public void setFirst(final T first) { this.first = first; }
      }
      """
    );

    assertTrue(
      errors.contains("cannot emit metadata constant for property 'first' of type 'X'"),
      () -> "expected the refusal naming the property and the subclass's variable, saw: " + errors
    );
  }
}
