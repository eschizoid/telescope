package io.github.telescopeorganization.telescope.unwalked;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;
import static io.github.telescopeorganization.telescope.mapping.Mapping.via;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/** The bridge converts the field that leads back to the parent through a class of its own. */
public record UvX(String name, UvBack back) {
  @Bridge
  public static final Mapper<UvX, UvXDto> DTO = mapper(
    UvX.class,
    UvXDto.class,
    to(UvX::name, UvXDto::label),
    via(UvX::back, UvXDto::back, UvBackMapper.class)
  );
}
