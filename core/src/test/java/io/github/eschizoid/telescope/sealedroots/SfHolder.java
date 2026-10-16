package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Holds a sealed root one of whose cases declares bridges to two targets. */
@Bridge(SfHolderDto.class)
public record SfHolder(SfShape shape) {}
