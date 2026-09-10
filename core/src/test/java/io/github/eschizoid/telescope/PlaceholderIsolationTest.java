package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.eschizoid.telescope.mapping.Mapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A target field with no same-name source is filled with a default-tree placeholder, and the
 * telescope row then writes into it. Caching the plan for building one must not collapse into
 * caching a built one: repeated conversions have to keep producing independent, correct results.
 *
 * <p>Note what this does <em>not</em> prove. The bean write path rebuilds through the writer rather
 * than mutating the placeholder, so a shared instance would not be visible through it and these
 * assertions pass either way. The reason for building per call is stated on the plan cache and is a
 * safety argument about handing one mutable default to every conversion on every thread, not a
 * behaviour these tests can gate.
 */
class PlaceholderIsolationTest {

  record Slim(String value) {}

  record Node(Node next, String value) {}

  record NodeHolder(String label, Node node) {}

  record Ping(Pong pong) {}

  record Pong(Ping ping) {}

  record PingHolder(String label, Ping ping) {}

  public static class Holder {

    private Leaf leaf;

    public Leaf getLeaf() {
      return leaf;
    }

    public void setLeaf(final Leaf leaf) {
      this.leaf = leaf;
    }
  }

  public static class Leaf {

    private String value;

    public String getValue() {
      return value;
    }

    public void setValue(final String value) {
      this.value = value;
    }
  }

  @Test
  @DisplayName("each conversion gets its own placeholder instance")
  void placeholdersAreNotSharedBetweenConversions() {
    final var mapper = Telescope.mapper(
      Slim.class,
      Holder.class,
      Mapping.to(Slim::value, Telescope.ofBean(Holder.class).field(Holder::getLeaf).field(Leaf::getValue))
    );

    final var first = mapper.forward(new Slim("first"));
    final var second = mapper.forward(new Slim("second"));

    assertNotNull(first.getLeaf());
    assertNotNull(second.getLeaf());
    assertNotSame(first.getLeaf(), second.getLeaf(), "conversions do not hand back one instance");
    assertEquals("first", first.getLeaf().getValue(), "the earlier conversion keeps its own value");
    assertEquals("second", second.getLeaf().getValue());
  }

  @Test
  @DisplayName("a self-referential record has no default, rather than no bound")
  void selfReferentialRecordYieldsNoDefault() {
    // No instance of Node can be built without an instance of Node, so it has no default tree.
    // Resolving its component plans re-enters the same type, which without a guard descends until
    // the stack ends. It yields the null a type with no construction strategy yields instead.
    final var mapper = Telescope.mapper(
      Slim.class,
      NodeHolder.class,
      Mapping.to(Slim::value, Telescope.of(NodeHolder.class).field(NodeHolder::node).field(Node::value))
    );

    // The write lands on nothing because there is nothing to land on: Node has no default tree, so
    // the placeholder is null and the row's set has no instance to rebuild. Terminating with that
    // null is the property — the alternative is descending until the stack ends.
    assertNull(mapper.forward(new Slim("x")).node(), "a record that cannot be defaulted stays null");
  }

  @Test
  @DisplayName("mutually referential records terminate the same way")
  void mutualReferenceTerminates() {
    final var mapper = Telescope.mapper(
      Slim.class,
      PingHolder.class,
      Mapping.to(Slim::value, Telescope.of(PingHolder.class).field(PingHolder::label))
    );

    // The row claims `label`, but resolving the target still plans a placeholder for `ping`, whose
    // component type resolves back to it.
    assertEquals("y", mapper.forward(new Slim("y")).label());
  }
}
