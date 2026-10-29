package io.github.eschizoid.telescope.ownbridge;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** A nested pair with its own bridge, holding a bean typed by a self-bounded variable. */
public record ObLadder(String label, ObRankA rank) {
  @Bridge
  public static final Mapper<ObLadder, ObLadderDto> DTO = mapper(
    ObLadder.class,
    ObLadderDto.class,
    to(ObLadder::label, ObLadderDto::label, ObUpperFn.class)
  );
}
