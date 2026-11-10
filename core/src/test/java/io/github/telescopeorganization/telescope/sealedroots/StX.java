package io.github.telescopeorganization.telescope.sealedroots;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

public record StX(String name, StBack back) {
  @Bridge
  public static final Mapper<StX, StXDto> DTO = mapper(
    StX.class,
    StXDto.class,
    to(StX::name, StXDto::name, SvUpper.class),
    to(StX::back, StXDto::back, StBackDrop.class)
  );
}
