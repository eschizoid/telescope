package io.github.telescopeorganization.telescope.ownbridge;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/**
 * A source declaring two targets, one on a {@code @Bridge} field whose rows transform its label and
 * one on the class, so the bridge to {@link ObMultiDto} is named by its pair.
 */
@Bridge(ObMultiView.class)
public record ObMulti(String label) {
  @Bridge
  public static final Mapper<ObMulti, ObMultiDto> DTO = mapper(
    ObMulti.class,
    ObMultiDto.class,
    to(ObMulti::label, ObMultiDto::label, ObUpperFn.class)
  );
}
