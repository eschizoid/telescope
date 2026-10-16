package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(ScLeafDto.class)
public record ScLeaf(int x) implements ScNode {}
