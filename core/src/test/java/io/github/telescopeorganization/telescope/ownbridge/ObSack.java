package io.github.telescopeorganization.telescope.ownbridge;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/** A nested pair with its own bridge, holding a container class of its own. */
public record ObSack(String label, ObBag bag) {
  @Bridge
  public static final Mapper<ObSack, ObSackDto> DTO = mapper(
    ObSack.class,
    ObSackDto.class,
    to(ObSack::label, ObSackDto::label, ObUpperFn.class)
  );
}
