package io.github.eschizoid.telescope.sealedroots;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

public record SbText(String body, int size) implements SbNote {
  @Bridge
  public static final Mapper<SbText, SbTextDto> DTO = mapper(
    SbText.class,
    SbTextDto.class,
    to(SbText::body, SbTextDto::text)
  );
}
