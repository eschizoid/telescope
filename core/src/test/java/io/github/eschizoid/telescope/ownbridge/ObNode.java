package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/** A type holding itself, whose own bridge upper-cases its label. */
@Bridge(value = ObNodeDto.class, transforms = @Transform(field = "label", using = ObUpperFn.class))
public record ObNode(String label, ObNode next) {}
