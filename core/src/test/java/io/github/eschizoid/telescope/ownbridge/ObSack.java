package io.github.eschizoid.telescope.ownbridge;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** A nested pair with its own bridge, holding a container class of its own. */
public record ObSack(String label, ObBag bag) {
  @Bridge
  public static final Mapper<ObSack, ObSackDto> DTO = mapper(
    ObSack.class,
    ObSackDto.class,
    to(ObSack::label, ObSackDto::label, ObUpperFn.class)
  );
}
