package io.github.telescopeorganization.telescope.sealedroots;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/** A case declaring bridges to two targets, the one to a case of the sealed target renaming. */
@Bridge(SfAOther.class)
public record SfA(int x) implements SfShape {
  @Bridge
  public static final Mapper<SfA, SfADto> DTO = mapper(SfA.class, SfADto.class, to(SfA::x, SfADto::width));
}
