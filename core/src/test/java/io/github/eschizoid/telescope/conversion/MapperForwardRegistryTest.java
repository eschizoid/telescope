package io.github.eschizoid.telescope.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code mapperForward(A, B)} with no rows must route through a registered carrier bridge when the
 * name-derived sibling probe misses — otherwise it silently degrades to same-name mapping and drops
 * the bridge's renames. The {@code (BridgeSrc, BridgeTgt)} pair has no {@code <BridgeSrc>Bridge}
 * sibling on the classpath, so the only way {@code beta} gets filled is via the registry.
 */
class MapperForwardRegistryTest {

  @Test
  @DisplayName("zero-row mapperForward routes through the registry-discovered bridge, applying the rename")
  void routesThroughRegisteredCarrierBridge() {
    final var mapper = Telescope.mapperForward(RegistryFixtures.BridgeSrc.class, RegistryFixtures.BridgeTgt.class);
    final var out = mapper.forward(new RegistryFixtures.BridgeSrc("hello"));
    // alpha -> beta rename only happens through the bridge; lenient same-name would leave beta
    // null.
    assertEquals("hello", out.beta());
  }
}
