package io.github.telescopeorganization.telescope.frommap;

import io.github.telescopeorganization.telescope.annotations.FromMap;

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
