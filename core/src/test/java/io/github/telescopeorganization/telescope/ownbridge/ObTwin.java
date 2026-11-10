package io.github.telescopeorganization.telescope.ownbridge;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/**
 * A source declaring two targets, one of them {@link ObTwinDto}, whose simple name a class in
 * another package shares with no bridge to it.
 */
@Bridge(ObTwinView.class)
public record ObTwin(String name) {
  @Bridge
  public static final Mapper<ObTwin, ObTwinDto> DTO = mapper(
    ObTwin.class,
    ObTwinDto.class,
    to(ObTwin::name, ObTwinDto::name, ObUpperFn.class)
  );
}
