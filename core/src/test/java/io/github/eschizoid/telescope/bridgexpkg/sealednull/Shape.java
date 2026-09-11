package io.github.eschizoid.telescope.bridgexpkg.sealednull;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Sealed source root whose umbrella bridge dispatches per permit through {@code Match}. */
@Bridge(ShapeDto.class)
public sealed interface Shape permits Circle, Square {}
