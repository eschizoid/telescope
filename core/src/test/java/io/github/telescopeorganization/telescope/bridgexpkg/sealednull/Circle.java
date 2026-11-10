package io.github.telescopeorganization.telescope.bridgexpkg.sealednull;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(CircleDto.class)
public record Circle(double radius) implements Shape {}
