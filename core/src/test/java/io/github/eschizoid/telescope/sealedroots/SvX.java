package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

@Bridge(
  value = SvXDto.class,
  transforms = {
    @Transform(field = "name", using = SvUpper.class), @Transform(field = "shape", using = SvShapeScale.class),
  }
)
public record SvX(String name, SvShape shape) {}
