package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;
import io.github.eschizoid.telescope.ownbridge.twin.ObSoloDto;

/**
 * A source whose short-form bridge converts to the {@code twin} package's {@code ObSoloDto}, while
 * {@link ObSoloBox} has the processor derive a long-form bridge to this package's class of that
 * name.
 */
@Bridge(value = ObSoloDto.class, transforms = @Transform(field = "name", using = ObUpperFn.class))
public record ObSolo(String name) {}
