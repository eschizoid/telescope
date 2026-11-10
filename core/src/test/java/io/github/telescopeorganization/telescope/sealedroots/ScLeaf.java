package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(ScLeafDto.class)
public record ScLeaf(int x) implements ScNode {}
