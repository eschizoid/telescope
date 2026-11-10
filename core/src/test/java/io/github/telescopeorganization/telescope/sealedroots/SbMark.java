package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(SbMarkDto.class)
public record SbMark(int weight) implements SbNote {}
