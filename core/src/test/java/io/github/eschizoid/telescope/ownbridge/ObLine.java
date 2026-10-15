package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/** A nested pair whose own bridge converts its status through a transform. */
@Bridge(value = ObLineDto.class, transforms = @Transform(field = "status", using = ObStatusFn.class))
public record ObLine(ObStatus status) {}
