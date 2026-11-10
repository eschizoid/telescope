package io.github.telescopeorganization.telescope.unwalked;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(UhLeafDto.class)
public record UhLeaf(int n) implements UhShape {}
