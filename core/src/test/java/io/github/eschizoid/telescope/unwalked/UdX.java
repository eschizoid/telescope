package io.github.eschizoid.telescope.unwalked;

import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;

/** The bridge drops the field that leads back to the parent. */
public record UdX(String name, UdBack back) {
  @Bridge
  public static final ForwardMapper<UdX, UdXDto> DTO = mapperForward(
    UdX.class,
    UdXDto.class,
    to(UdX::name, UdXDto::label),
    drop(UdX::back)
  );
}
