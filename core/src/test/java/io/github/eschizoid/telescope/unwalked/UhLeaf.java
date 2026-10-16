package io.github.eschizoid.telescope.unwalked;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(UhLeafDto.class)
public record UhLeaf(int n) implements UhShape {}
