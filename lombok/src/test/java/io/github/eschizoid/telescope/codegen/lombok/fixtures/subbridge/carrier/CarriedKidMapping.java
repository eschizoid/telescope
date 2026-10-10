package io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.carrier;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.CarriedKid;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.subbridge.CarriedKidDto;

/** Fixture: the carrier declaring the {@link CarriedKid} pair from a package of its own. */
@Bridge(source = CarriedKid.class, target = CarriedKidDto.class)
public final class CarriedKidMapping {

  private CarriedKidMapping() {}
}
