package io.github.telescopeorganization.telescope.ownbridge.carrier;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;
import io.github.telescopeorganization.telescope.ownbridge.ObPin;
import io.github.telescopeorganization.telescope.ownbridge.ObPinDto;
import io.github.telescopeorganization.telescope.ownbridge.ObStatusFn;

/** A {@code @Bridge} field for a nested pair, in a package of its own. */
public final class ObPinCarrier {

  private ObPinCarrier() {}

  @Bridge
  public static final Mapper<ObPin, ObPinDto> PIN = mapper(
    ObPin.class,
    ObPinDto.class,
    to(ObPin::status, ObPinDto::status, ObStatusFn.class)
  );
}
