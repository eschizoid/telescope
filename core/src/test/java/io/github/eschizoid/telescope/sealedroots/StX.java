package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

@Bridge(
  value = StXDto.class,
  transforms = {
    @Transform(field = "name", using = SvUpper.class), @Transform(field = "back", using = StBackDrop.class),
  }
)
public record StX(String name, StBack back) {}
