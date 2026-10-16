package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(ScDotDto.class)
public record ScDot(int x) implements ScShape {}
