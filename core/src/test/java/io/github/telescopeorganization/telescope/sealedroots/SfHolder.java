package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Holds a sealed root one of whose cases declares bridges to two targets. */
@Bridge(SfHolderDto.class)
public record SfHolder(SfShape shape) {}
