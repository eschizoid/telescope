package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A source field no row reads, whose same-named target a row reading another source field writes,
 * has nowhere to be written. A strict mapper refuses it by name at any pair that has rows of its
 * own, whatever other rows the pair has; a forward-only mapper leaves it unread.
 */
class KeptFieldRefusalTest {

  record Src(String n, String label) {}

  record Tgt(String label, String z) {}

  record Inner(String n, String label) {}

  record InnerDto(String label) {}

  record Outer(String id, Inner in) {}

  record OuterDto(String id, InnerDto in) {}

  private static final String REFUSAL = "source field 'label' has no same-name target field";

  @Test
  @DisplayName("a strict mapper refuses the kept field beside a compute row on another target")
  void besideAComputeRow() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Src.class, Tgt.class, to(Src::n, Tgt::label), compute(Tgt::z, () -> "made"))
    );
    assertTrue(refused.getMessage().contains(REFUSAL), refused.getMessage());
  }

  @Test
  @DisplayName("a strict mapper refuses the kept field beside a constant row on another target")
  void besideAConstantRow() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Src.class, Tgt.class, to(Src::n, Tgt::label), constant(Tgt::z, "k"))
    );
    assertTrue(refused.getMessage().contains(REFUSAL), refused.getMessage());
  }

  @Test
  @DisplayName("a strict mapper refuses the kept field of a nested pair that has a row of its own")
  void atANestedPairWithRows() {
    final var refused = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Outer.class, OuterDto.class, to(Inner::n, InnerDto::label))
    );
    assertTrue(refused.getMessage().contains("Inner → InnerDto: " + REFUSAL), refused.getMessage());
  }

  @Test
  @DisplayName("a forward-only mapper leaves the kept field unread and writes the renamed one")
  void forwardOnlyLeavesItUnread() {
    final var mapper = Telescope.mapperForward(
      Src.class,
      Tgt.class,
      to(Src::n, Tgt::label),
      compute(Tgt::z, () -> "made")
    );
    assertEquals(new Tgt("7", "made"), mapper.forward(new Src("7", "own")));
  }
}
