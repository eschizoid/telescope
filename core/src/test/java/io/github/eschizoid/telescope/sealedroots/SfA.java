package io.github.eschizoid.telescope.sealedroots;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** A case declaring bridges to two targets, the one to a case of the sealed target renaming. */
@Bridge(SfAOther.class)
public record SfA(int x) implements SfShape {
  @Bridge
  public static final Mapper<SfA, SfADto> DTO = mapper(SfA.class, SfADto.class, to(SfA::x, SfADto::width));
}
