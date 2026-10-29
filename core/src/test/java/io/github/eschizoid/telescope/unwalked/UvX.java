package io.github.eschizoid.telescope.unwalked;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

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
