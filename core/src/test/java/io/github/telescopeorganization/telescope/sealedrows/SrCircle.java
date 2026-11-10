package io.github.telescopeorganization.telescope.sealedrows;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/** A case whose own bridge fills the title from the label. */
public record SrCircle(String name, String label) implements SrShape {
  @Bridge
  public static final Mapper<SrCircle, SrCircleDto> DTO = mapper(
    SrCircle.class,
    SrCircleDto.class,
    to(SrCircle::label, SrCircleDto::title)
  );
}
