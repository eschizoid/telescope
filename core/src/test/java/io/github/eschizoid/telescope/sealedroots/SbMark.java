package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SbMarkDto.class)
public record SbMark(int weight) implements SbNote {}
