package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

@Bridge(SmCatDto.class)
public record SmCat(String n, String m) implements SmMammal {}
