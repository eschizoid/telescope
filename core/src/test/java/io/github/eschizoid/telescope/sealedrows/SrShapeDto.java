package io.github.eschizoid.telescope.sealedrows;

/** A target interface whose accessors rows name. */
public sealed interface SrShapeDto permits SrCircleDto, SrSquareDto {
  String name();

  String title();
}
