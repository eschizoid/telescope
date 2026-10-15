package io.github.eschizoid.telescope.unwalked;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A runtime mapper decides whether a nested pair's own bridge may convert it from the properties
 * that bridge walks. Each fixture's bridge renames {@code name} to {@code label}, which only the
 * bridge does, and handles {@code back}, the field leading back to the parent, one way the bridge
 * does not walk it. The bridge converting the pair shows as a label; the mapper walking it, as
 * none.
 */
class UnwalkedPropertiesTest {

  @Test
  @DisplayName("a dropped field leading back to the parent leaves the pair to its own bridge")
  void aDroppedFieldLeavesThePairToItsBridge() {
    final var dto = Telescope.mapper(UdTop.class, UdTopDto.class).forward(new UdTop(new UdX("ab", new UdBack(null))));
    assertEquals("ab", dto.x().label());
  }

  @Test
  @DisplayName("a field converted through a @ViaMapper leaves the pair to its own bridge")
  void aViaMapperFieldLeavesThePairToItsBridge() {
    final var dto = Telescope.mapper(UvTop.class, UvTopDto.class).forward(new UvTop(new UvX("ab", new UvBack(null))));
    assertEquals(new UvXDto("ab", new UvBackDto(null)), dto.x());
  }

  @Test
  @DisplayName("a target field a @Constant fills leaves the pair to its own bridge")
  void aConstantFieldLeavesThePairToItsBridge() {
    final var dto = Telescope.mapperForward(UkTop.class, UkTopDto.class).forward(
      new UkTop(new UkX("ab", new UkBack(null)))
    );
    assertEquals(new UkXDto("ab", new UkBackLite(), null), dto.x());
  }

  @Test
  @DisplayName("a target field a @Compute fills leaves the pair to its own bridge")
  void aComputedFieldLeavesThePairToItsBridge() {
    final var dto = Telescope.mapperForward(UcTop.class, UcTopDto.class).forward(
      new UcTop(new UcX("ab", new UcBack(null)))
    );
    assertEquals(new UcXDto("ab", new UcBackLite(), new UcBackDto(null)), dto.x());
  }

  @Test
  @DisplayName("a hand-written bridge that lists nothing converts its pair as it did before sealed fields converted")
  void aHandWrittenBridgeConvertsItsPair() {
    final var dto = Telescope.mapper(UhTop.class, UhTopDto.class).forward(new UhTop(new UhX("ab", new UhLeaf(1))));
    assertEquals(new UhXDto("AB", new UhLeafDto(100)), dto.x());
  }
}
