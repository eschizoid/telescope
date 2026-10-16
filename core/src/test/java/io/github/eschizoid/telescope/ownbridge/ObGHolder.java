package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/**
 * A nested pair with its own bridge, holding a bean that inherits its property from a generic base.
 */
@Bridge(value = ObGHolderDto.class, transforms = @Transform(field = "label", using = ObUpperFn.class))
public record ObGHolder(String label, ObGSub sub) {}
