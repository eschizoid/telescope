package io.github.eschizoid.telescope.bridgecarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code @Bridge} field declared in another package from its pair ({@code wiring.CarrierDef}) is
 * found by a zero-row {@code mapperForward} through the generated {@code BridgeProvider}
 * registration, and its rename row is applied. Mapped by name instead, {@code renamedName} comes
 * back null.
 */
class CarrierBridgeAutodiscoverTest {

  @Test
  @DisplayName("zero-row mapperForward finds a @Bridge field declared in another package and applies its rename")
  void carrierBridgeAutodiscovered() {
    final var mapper = Telescope.mapperForward(CarrierSrc.class, CarrierTgt.class);
    final var out = mapper.forward(new CarrierSrc("hello"));
    assertEquals("hello", out.renamedName());
  }
}
