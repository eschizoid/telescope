package io.github.eschizoid.telescope.unwalked;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Constant;
import io.github.eschizoid.telescope.annotations.Rename;

/** The bridge fills the only target field that leads back to the parent with a constant. */
@Bridge(
  value = UkXDto.class,
  renames = @Rename(source = "name", target = "label"),
  lenient = true,
  constants = @Constant(field = "extra", value = "null")
)
public record UkX(String name, UkBack back) {}
