package io.github.eschizoid.telescope.ownbridge.twin;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.ownbridge.ObCarried;
import io.github.eschizoid.telescope.ownbridge.ObUpperFn;

/** A {@code @Bridge} field converting to this package's {@link ObCarriedDto}. */
public final class ObCarriedCarrier {

  private ObCarriedCarrier() {}

  @Bridge
  public static final Mapper<ObCarried, ObCarriedDto> CARRIED = mapper(
    ObCarried.class,
    ObCarriedDto.class,
    to(ObCarried::name, ObCarriedDto::name, ObUpperFn.class)
  );
}
