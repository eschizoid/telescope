package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(ShDDto.class)
public record ShD(int y) implements ShShape {}
