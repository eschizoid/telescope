package io.github.eschizoid.telescope.bridgexpkg.sealednull;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(CircleDto.class)
public record Circle(double radius) implements Shape {}
