package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/** A nested pair whose own bridge upper-cases its text into a bean. */
@Bridge(value = ObNoteBean.class, transforms = @Transform(field = "text", using = ObUpperFn.class))
public record ObNote(String text) {}
