package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(SvLeafDto.class)
public record SvLeaf(int n) implements SvShape {}
