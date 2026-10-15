package io.github.eschizoid.telescope.ownbridge.twin;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;
import io.github.eschizoid.telescope.ownbridge.ObCarried;
import io.github.eschizoid.telescope.ownbridge.ObUpperFn;

/** A carrier bridge to this package's {@link ObCarriedDto}. */
@Bridge(
  source = ObCarried.class,
  target = ObCarriedDto.class,
  transforms = @Transform(field = "name", using = ObUpperFn.class)
)
public class ObCarriedCarrier {}
