package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/** A source declaring two targets, so each bridge gets a long-form name. */
@Bridge(value = ObMultiDto.class, transforms = @Transform(field = "label", using = ObUpperFn.class))
@Bridge(ObMultiView.class)
public record ObMulti(String label) {}
