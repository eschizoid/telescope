package io.github.telescopeorganization.telescope.bridgexpkg.sealednull;

/** Sealed target root; each permit pairs with one permit of {@link Shape}. */
public sealed interface ShapeDto permits CircleDto, SquareDto {}
