package io.github.eschizoid.telescope.unwalked;

import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;

/** The bridge fills the only target field that leads back to the parent with a constant. */
public record UkX(String name, UkBack back) {
  @Bridge
  public static final ForwardMapper<UkX, UkXDto> DTO = mapperForward(
    UkX.class,
    UkXDto.class,
    to(UkX::name, UkXDto::label),
    constant(UkXDto::extra, null)
  );
}
