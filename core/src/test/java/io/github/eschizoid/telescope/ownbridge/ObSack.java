package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/** A nested pair with its own bridge, holding a container class of its own. */
@Bridge(value = ObSackDto.class, transforms = @Transform(field = "label", using = ObUpperFn.class))
public record ObSack(String label, ObBag bag) {}
