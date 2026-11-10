package io.github.telescopeorganization.telescope.bridgexpkg.sealednull;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(SquareDto.class)
public record Square(double side) implements Shape {}
