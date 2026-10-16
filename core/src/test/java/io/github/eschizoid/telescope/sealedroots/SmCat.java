package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(SmCatDto.class)
public record SmCat(String n, String m) implements SmMammal {}
