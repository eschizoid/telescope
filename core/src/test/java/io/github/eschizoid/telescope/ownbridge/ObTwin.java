package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/**
 * A source declaring two targets, one of them {@link ObTwinDto}, whose simple name a class in
 * another package shares with no bridge to it.
 */
@Bridge(value = ObTwinDto.class, transforms = @Transform(field = "name", using = ObUpperFn.class))
@Bridge(ObTwinView.class)
public record ObTwin(String name) {}
