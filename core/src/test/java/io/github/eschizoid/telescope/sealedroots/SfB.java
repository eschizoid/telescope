package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SfBDto.class)
public record SfB(int y) implements SfShape {}
