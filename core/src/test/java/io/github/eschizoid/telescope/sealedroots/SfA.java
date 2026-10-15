package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SfADto.class)
@Bridge(SfAOther.class)
public record SfA(int x) implements SfShape {}
