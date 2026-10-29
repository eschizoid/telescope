package io.github.eschizoid.telescope.sealedroots;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

public record SvX(String name, SvShape shape) {
  @Bridge
  public static final Mapper<SvX, SvXDto> DTO = mapper(
    SvX.class,
    SvXDto.class,
    to(SvX::name, SvXDto::name, SvUpper.class),
    to(SvX::shape, SvXDto::shape, SvShapeScale.class)
  );
}
