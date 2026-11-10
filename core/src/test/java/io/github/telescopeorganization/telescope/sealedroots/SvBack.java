package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(SvBackDto.class)
public record SvBack(SvTop t) implements SvShape {}
