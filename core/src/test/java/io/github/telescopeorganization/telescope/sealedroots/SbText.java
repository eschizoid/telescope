package io.github.telescopeorganization.telescope.sealedroots;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

public record SbText(String body, int size) implements SbNote {
  @Bridge
  public static final Mapper<SbText, SbTextDto> DTO = mapper(
    SbText.class,
    SbTextDto.class,
    to(SbText::body, SbTextDto::text)
  );
}
