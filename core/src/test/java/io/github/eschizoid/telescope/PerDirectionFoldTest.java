package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.MhIso;
import io.github.eschizoid.telescope.mapping.MapStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which directions of a bean pair the composed leaf builds. A direction builds one side and only
 * reads the other, so whether it composes depends on the side it builds alone: a source no writer
 * can build still lets the forward direction, which only reads it, compose — and the backward
 * direction, which would build it, goes to the array leaf and refuses there as before.
 */
class PerDirectionFoldTest {

  /** Readable through its getter, and buildable by no writer: its one constructor names nothing. */
  public static class ReadOnlySource {

    private final String name;

    public ReadOnlySource(final String other) {
      this.name = other;
    }

    public String getName() {
      return name;
    }
  }

  /** Written through its setters. */
  public static class SetterTarget {

    private String name;

    public SetterTarget() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  @Test
  @DisplayName("a source no writer can build still composes the forward direction")
  void forwardComposesWhenOnlyTheSourceCannotBeBuilt() {
    final var iso = DeepMap.resolve(ReadOnlySource.class, SetterTarget.class, new MapStep[0]);

    assertTrue(MhIso.isComposedLeaf(iso), "the forward direction only reads the source");
    assertEquals("a", iso.to(new ReadOnlySource("a")).getName());
  }

  @Test
  @DisplayName("the backward direction, which would build that source, still refuses")
  void backwardStillRefuses() {
    final var iso = DeepMap.resolve(ReadOnlySource.class, SetterTarget.class, new MapStep[0]);
    final var target = new SetterTarget();
    target.setName("a");

    assertThrows(IllegalStateException.class, () -> iso.from(target));
  }

  @Test
  @DisplayName("a pair whose built sides no composed build reproduces stays on the array leaf")
  void neitherDirectionComposes() {
    final var iso = DeepMap.resolve(ReadOnlySource.class, ReadOnlySource.class, new MapStep[0]);

    assertFalse(MhIso.isComposedLeaf(iso));
  }
}
