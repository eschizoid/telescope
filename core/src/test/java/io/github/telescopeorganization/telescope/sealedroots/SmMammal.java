package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(SmMammalDto.class)
public sealed interface SmMammal extends SmAnimal permits SmCat {}
