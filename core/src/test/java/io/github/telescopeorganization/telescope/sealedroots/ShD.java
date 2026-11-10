package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(ShDDto.class)
public record ShD(int y) implements ShShape {}
