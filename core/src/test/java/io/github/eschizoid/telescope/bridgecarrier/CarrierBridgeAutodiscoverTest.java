package io.github.eschizoid.telescope.bridgecarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof of the carrier-form auto-discover fix: a cross-package carrier {@code @Bridge}
 * (declared in {@code wiring.CarrierDef}) is found by zero-row {@code mapperForward} through the
 * generated {@code BridgeProvider} registration, and its {@code @Rename} is applied. Without the
 * registry path this silently degrades to same-name mapping and {@code renamedName} comes back
 * null.
 */
class CarrierBridgeAutodiscoverTest {

  @Test
  @DisplayName("zero-row mapperForward auto-discovers a cross-package carrier @Bridge and applies its rename")
  void carrierBridgeAutodiscovered() {
    final var mapper = Telescope.mapperForward(CarrierSrc.class, CarrierTgt.class);
    final var out = mapper.forward(new CarrierSrc("hello"));
    assertEquals("hello", out.renamedName());
  }
}
