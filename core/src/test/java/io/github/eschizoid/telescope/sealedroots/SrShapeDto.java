package io.github.eschizoid.telescope.sealedroots;

/** The sealed target root {@link SrShape} maps to. */
public sealed interface SrShapeDto permits SrCircleDto, SrSquareDto {}
