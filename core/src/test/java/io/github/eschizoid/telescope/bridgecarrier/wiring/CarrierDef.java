package io.github.eschizoid.telescope.bridgecarrier.wiring;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.bridgecarrier.CarrierSrc;
import io.github.eschizoid.telescope.bridgecarrier.CarrierTgt;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * A third class, in a different package from both source and target, declaring the mapping (and a
 * rename) for {@code CarrierSrc → CarrierTgt} on a {@code @Bridge} field. The generated {@code
 * CarrierSrcToCarrierTgtBridge} lives here in this class's package, which the source-keyed name
 * probe does not read, so it must be found through the {@code BridgeProvider} registry.
 */
public final class CarrierDef {

  private CarrierDef() {}

  @Bridge
  public static final Mapper<CarrierSrc, CarrierTgt> TGT = mapper(
    CarrierSrc.class,
    CarrierTgt.class,
    to(CarrierSrc::legacyName, CarrierTgt::renamedName)
  );
}
