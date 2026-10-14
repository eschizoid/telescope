package io.github.eschizoid.telescope.ownbridge.carrier;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;
import io.github.eschizoid.telescope.ownbridge.ObPin;
import io.github.eschizoid.telescope.ownbridge.ObPinDto;
import io.github.eschizoid.telescope.ownbridge.ObStatusFn;

/** A carrier bridge for a nested pair, in a package of its own. */
@Bridge(
  source = ObPin.class,
  target = ObPinDto.class,
  transforms = @Transform(field = "status", using = ObStatusFn.class)
)
public class ObPinCarrier {}
