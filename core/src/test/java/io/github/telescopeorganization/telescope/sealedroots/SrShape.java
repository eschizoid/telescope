package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** A sealed source root whose cases each carry a bridge to a case of {@link SrShapeDto}. */
@Bridge(SrShapeDto.class)
public sealed interface SrShape permits SrCircle, SrSquare {}
