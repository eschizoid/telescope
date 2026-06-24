package io.github.eschizoid.telescope.bridgecarrier.wiring;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Rename;
import io.github.eschizoid.telescope.bridgecarrier.CarrierSrc;
import io.github.eschizoid.telescope.bridgecarrier.CarrierTgt;

/**
 * Carrier-form {@code @Bridge}: a third class, in a different package from both source and target,
 * declaring the mapping (and a rename) for {@code CarrierSrc → CarrierTgt}. The generated {@code
 * CarrierDefBridge} lives here in the carrier's package — unreachable by the source-keyed name
 * probe — so it must be auto-discovered through the {@code BridgeProvider} registry.
 */
@Bridge(
  source = CarrierSrc.class,
  target = CarrierTgt.class,
  renames = @Rename(source = "legacyName", target = "renamedName")
)
public class CarrierDef {}
