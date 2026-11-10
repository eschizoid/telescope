package io.github.telescopeorganization.telescope.ownbridge;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/**
 * A nested pair with its own bridge, holding a bean that inherits its property from a generic base.
 */
public record ObGHolder(String label, ObGSub sub) {
  @Bridge
  public static final Mapper<ObGHolder, ObGHolderDto> DTO = mapper(
    ObGHolder.class,
    ObGHolderDto.class,
    to(ObGHolder::label, ObGHolderDto::label, ObUpperFn.class)
  );
}
