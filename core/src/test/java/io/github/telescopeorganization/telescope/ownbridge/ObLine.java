package io.github.telescopeorganization.telescope.ownbridge;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/** A nested pair whose own bridge converts its status through a conversion class. */
public record ObLine(ObStatus status) {
  @Bridge
  public static final Mapper<ObLine, ObLineDto> DTO = mapper(
    ObLine.class,
    ObLineDto.class,
    to(ObLine::status, ObLineDto::status, ObStatusFn.class)
  );
}
