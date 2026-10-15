package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SmMammalDto.class)
public sealed interface SmMammal extends SmAnimal permits SmCat {}
