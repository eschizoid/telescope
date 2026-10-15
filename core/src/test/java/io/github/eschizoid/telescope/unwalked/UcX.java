package io.github.eschizoid.telescope.unwalked;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Compute;
import io.github.eschizoid.telescope.annotations.Rename;

/** The bridge fills the only target field that leads back to the parent with a computed value. */
@Bridge(
  value = UcXDto.class,
  renames = @Rename(source = "name", target = "label"),
  lenient = true,
  computes = @Compute(field = "extra", using = UcBackSupplier.class)
)
public record UcX(String name, UcBack back) {}
