package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(SrSquareDto.class)
public record SrSquare(double side, String label) implements SrShape {}
