package io.github.telescopeorganization.telescope.bridgexpkg.sealednull;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Sealed source root whose umbrella bridge dispatches per permit through {@code Match}. */
@Bridge(ShapeDto.class)
public sealed interface Shape permits Circle, Square {}
