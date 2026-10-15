package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SvLeafDto.class)
public record SvLeaf(int n) implements SvShape {}
