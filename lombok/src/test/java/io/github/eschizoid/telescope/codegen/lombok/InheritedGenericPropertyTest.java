package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.ChainBase;
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
 * subclass passes a variable of its own through. A runtime path through it rebuilds the subclass,
 * as the navigator does, since the superclass that declares the getter cannot be constructed.
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
  @DisplayName("a runtime write of an inherited self-bound property rebuilds the subclass, as the navigator does")
  void runtimeWriteOfAnInheritedPropertyMatchesTheNavigator() {
    final var head = new ChainNode();
    head.setLabel("head");
    final var tail = new ChainNode();
    tail.setLabel("tail");

    final var runtime = Telescope.ofBean(ChainNode.class).field(ChainNode::getNext).set(head, tail);
    final var navigator = ChainNodeTelescope.of().next().set(head, tail);

    assertSame(ChainNode.class, runtime.getClass());
    assertSame(tail, runtime.getNext());
    assertSame(navigator.getNext(), runtime.getNext());
    assertEquals(navigator.getLabel(), runtime.getLabel());
    assertNull(head.getNext(), "the source is not mutated");
  }

  @Test
  @DisplayName("a runtime path through a superclass-qualified getter and on into the subclass matches the navigator")
  void runtimePathThroughASuperclassQualifiedGetterMatchesTheNavigator() {
    final var head = new ChainNode();
    head.setLabel("head");
    final var tail = new ChainNode();
    tail.setLabel("a");
    head.setNext(tail);

    final var runtime = Telescope.ofBean(ChainNode.class)
      .field(ChainBase<ChainNode>::getNext)
      .field(ChainNode::getLabel)
      .set(head, "b");
    final var navigator = ChainNodeTelescope.of().next().label().set(head, "b");

    assertSame(ChainNode.class, runtime.getClass());
    assertSame(ChainNode.class, runtime.getNext().getClass());
    assertEquals(navigator.getNext().getLabel(), runtime.getNext().getLabel());
    assertEquals(navigator.getLabel(), runtime.getLabel());
    assertEquals("b", runtime.getNext().getLabel());
    assertEquals("head", runtime.getLabel());
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
