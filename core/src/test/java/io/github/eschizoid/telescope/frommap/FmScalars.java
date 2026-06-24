package io.github.eschizoid.telescope.frommap;

import io.github.eschizoid.telescope.annotations.FromMap;

/** Every primitive + a couple of boxed wrappers, to pin the parse/null behavior. */
@FromMap
public record FmScalars(
  boolean active,
  float ratio,
  short small,
  byte tiny,
  char letter,
  Integer boxedInt,
  Boolean boxedBool
) {}
