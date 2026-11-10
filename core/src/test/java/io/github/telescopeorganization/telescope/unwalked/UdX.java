package io.github.telescopeorganization.telescope.unwalked;

import static io.github.telescopeorganization.telescope.Telescope.mapperForward;
import static io.github.telescopeorganization.telescope.mapping.Mapping.drop;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.ForwardMapper;

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
