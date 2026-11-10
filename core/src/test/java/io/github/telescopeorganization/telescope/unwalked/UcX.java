package io.github.telescopeorganization.telescope.unwalked;

import static io.github.telescopeorganization.telescope.Telescope.mapperForward;
import static io.github.telescopeorganization.telescope.mapping.Mapping.compute;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.ForwardMapper;

/** The bridge fills the only target field that leads back to the parent with a computed value. */
public record UcX(String name, UcBack back) {
  @Bridge
  public static final ForwardMapper<UcX, UcXDto> DTO = mapperForward(
    UcX.class,
    UcXDto.class,
    to(UcX::name, UcXDto::label),
    compute(UcXDto::extra, UcBackSupplier.INSTANCE)
  );
}
