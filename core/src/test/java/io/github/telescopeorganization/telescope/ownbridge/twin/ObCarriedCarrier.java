package io.github.telescopeorganization.telescope.ownbridge.twin;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;
import io.github.telescopeorganization.telescope.ownbridge.ObCarried;
import io.github.telescopeorganization.telescope.ownbridge.ObUpperFn;

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
