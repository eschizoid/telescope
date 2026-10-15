package io.github.eschizoid.telescope.sealedroots;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Rename;

@Bridge(value = SbTextDto.class, renames = @Rename(source = "body", target = "text"))
public record SbText(String body, int size) implements SbNote {}
