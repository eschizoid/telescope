package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(ScDotDto.class)
public record ScDot(int x) implements ScShape {}
