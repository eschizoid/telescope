package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Holds an {@link ObSolo} converted to this package's {@link ObSoloDto}, a pair it derives. */
@Bridge(ObSoloBoxDto.class)
public record ObSoloBox(ObSolo solo) {}
