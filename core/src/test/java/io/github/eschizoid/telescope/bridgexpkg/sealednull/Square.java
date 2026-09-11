package io.github.eschizoid.telescope.bridgexpkg.sealednull;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SquareDto.class)
public record Square(double side) implements Shape {}
