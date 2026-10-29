package io.github.eschizoid.telescope.ownbridge.carrier;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.ownbridge.ObPin;
import io.github.eschizoid.telescope.ownbridge.ObPinDto;
import io.github.eschizoid.telescope.ownbridge.ObStatusFn;

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
