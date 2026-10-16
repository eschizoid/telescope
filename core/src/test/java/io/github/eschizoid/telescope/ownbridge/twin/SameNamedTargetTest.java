package io.github.eschizoid.telescope.ownbridge.twin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.ownbridge.ObCarried;
import io.github.eschizoid.telescope.ownbridge.ObSolo;
import io.github.eschizoid.telescope.ownbridge.ObTwin;
import io.github.eschizoid.telescope.ownbridge.ObTwinHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bridge's long name carries only its target's simple name, so a target in another package with
 * the same simple name is not the bridge's: the pair converts through a bridge to that target when
 * one exists, and is mapped by name otherwise.
 */
class SameNamedTargetTest {

  @Test
  @DisplayName("a nested pair whose target shares a bridged target's simple name in another package is walked by name")
  void aNestedPairWithASameNamedTargetIsWalked() {
    final var mapper = Telescope.mapper(ObTwinHolder.class, ObTwinHolderDto.class);
    assertEquals(new ObTwinHolderDto(new ObTwinDto("bob")), mapper.forward(new ObTwinHolder(new ObTwin("bob"))));
    assertEquals(new ObTwinHolder(new ObTwin("BOB")), mapper.backward(new ObTwinHolderDto(new ObTwinDto("BOB"))));
  }

  @Test
  @DisplayName(
    "a mapper with no steps passes over a long-form bridge to another target and converts through the short form"
  )
  void aWrongTargetLongFormBridgeFallsThroughToTheShortForm() {
    assertEquals(
      new ObSoloDto("BOB"),
      Telescope.mapperForward(ObSolo.class, ObSoloDto.class).forward(new ObSolo("bob"))
    );
  }

  @Test
  @DisplayName("a mapper with no steps passes over a long-form bridge to another target and converts through a carrier")
  void aWrongTargetLongFormBridgeFallsThroughToACarrier() {
    assertEquals(
      new ObCarriedDto("BOB"),
      Telescope.mapperForward(ObCarried.class, ObCarriedDto.class).forward(new ObCarried("bob"))
    );
  }

  @Test
  @DisplayName("a mapper with no steps to a target sharing a bridged target's simple name maps by name")
  void aMapperWithNoStepsToASameNamedTargetMapsByName() {
    assertEquals(
      new ObTwinDto("bob"),
      Telescope.mapperForward(ObTwin.class, ObTwinDto.class).forward(new ObTwin("bob"))
    );
  }
}
