package io.github.eschizoid.telescope.ownbridge;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * Holds an {@link ObCarried} converted to this package's {@link ObCarriedDto}, a pair it derives.
 */
@Bridge(ObCarriedBoxDto.class)
public record ObCarriedBox(ObCarried carried) {}
