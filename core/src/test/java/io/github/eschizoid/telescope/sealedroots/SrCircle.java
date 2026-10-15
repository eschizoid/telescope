package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SrCircleDto.class)
public record SrCircle(double radius) implements SrShape {}
