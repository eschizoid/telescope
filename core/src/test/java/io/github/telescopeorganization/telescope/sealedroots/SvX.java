package io.github.telescopeorganization.telescope.sealedroots;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

public record SvX(String name, SvShape shape) {
  @Bridge
  public static final Mapper<SvX, SvXDto> DTO = mapper(
    SvX.class,
    SvXDto.class,
    to(SvX::name, SvXDto::name, SvUpper.class),
    to(SvX::shape, SvXDto::shape, SvShapeScale.class)
  );
}
