package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(SfBDto.class)
public record SfB(int y) implements SfShape {}
