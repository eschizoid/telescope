package io.github.eschizoid.telescope.ownbridge;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** A nested pair whose own bridge upper-cases its label, with a pair of its own below it. */
public record ObParcel(String label, ObTag tag) {
  @Bridge
  public static final Mapper<ObParcel, ObParcelDto> DTO = mapper(
    ObParcel.class,
    ObParcelDto.class,
    to(ObParcel::label, ObParcelDto::label, ObUpperFn.class)
  );
}
