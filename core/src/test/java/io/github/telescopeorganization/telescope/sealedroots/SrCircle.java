package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(SrCircleDto.class)
public record SrCircle(double radius) implements SrShape {}
