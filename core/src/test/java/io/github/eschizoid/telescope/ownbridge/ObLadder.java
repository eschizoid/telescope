package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/** A nested pair with its own bridge, holding a bean typed by a self-bounded variable. */
@Bridge(value = ObLadderDto.class, transforms = @Transform(field = "label", using = ObUpperFn.class))
public record ObLadder(String label, ObRankA rank) {}
