package io.github.eschizoid.telescope.unwalked;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Rename;
import io.github.eschizoid.telescope.annotations.ViaMapper;

/** The bridge converts the field that leads back to the parent through a mapper of its own. */
@Bridge(
  value = UvXDto.class,
  renames = @Rename(source = "name", target = "label"),
  viaMappers = @ViaMapper(field = "back", using = UvBackMapper.class)
)
public record UvX(String name, UvBack back) {}
