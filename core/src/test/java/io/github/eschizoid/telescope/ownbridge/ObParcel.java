package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/** A nested pair whose own bridge upper-cases its label, with a pair of its own below it. */
@Bridge(value = ObParcelDto.class, transforms = @Transform(field = "label", using = ObUpperFn.class))
public record ObParcel(String label, ObTag tag) {}
