package io.github.eschizoid.telescope.bridgexpkg.sealednull;

import io.github.eschizoid.telescope.annotations.Bridge;

/** The non-sealed control: a plain bridged record sitting in the same parent as {@link Shape}. */
@Bridge(LabelDto.class)
public record Label(String text) {}
