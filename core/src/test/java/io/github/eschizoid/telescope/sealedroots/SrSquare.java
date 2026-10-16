package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SrSquareDto.class)
public record SrSquare(double side, String label) implements SrShape {}
