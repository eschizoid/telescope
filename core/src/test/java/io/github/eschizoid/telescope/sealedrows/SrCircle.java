package io.github.eschizoid.telescope.sealedrows;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Rename;

/** A case whose own bridge fills the title from the label. */
@Bridge(value = SrCircleDto.class, renames = @Rename(source = "label", target = "title"))
public record SrCircle(String name, String label) implements SrShape {}
