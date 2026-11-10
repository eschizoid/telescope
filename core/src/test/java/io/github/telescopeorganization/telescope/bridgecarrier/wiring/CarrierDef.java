package io.github.telescopeorganization.telescope.bridgecarrier.wiring;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.bridgecarrier.CarrierSrc;
import io.github.telescopeorganization.telescope.bridgecarrier.CarrierTgt;
import io.github.telescopeorganization.telescope.conversion.Mapper;

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
