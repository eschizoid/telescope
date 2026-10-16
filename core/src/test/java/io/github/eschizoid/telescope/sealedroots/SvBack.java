package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SvBackDto.class)
public record SvBack(SvTop t) implements SvShape {}
