package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

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
  @DisplayName("a placeholder built once is still built on every later conversion")
  void repeatedConversionsKeepBuilding() {
    final var mapper = Telescope.mapper(
      Slim.class,
      Holder.class,
      Mapping.to(Slim::value, Telescope.ofBean(Holder.class).field(Holder::getLeaf).field(Leaf::getValue))
    );

    // Ten conversions, ten distinct leaves: caching the plan must not collapse into caching one
    // instance, however many times the plan is reused.
    final var leaves = new java.util.IdentityHashMap<Leaf, Boolean>();
    for (var i = 0; i < 10; i++) leaves.put(mapper.forward(new Slim("v" + i)).getLeaf(), Boolean.TRUE);

    assertEquals(10, leaves.size(), "every conversion allocated its own placeholder");
  }
}
