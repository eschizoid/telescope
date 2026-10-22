package io.github.eschizoid.telescope.sealedrows;

/** A target interface one of whose cases has no title property. */
public sealed interface SrOddDto permits SrCircleDto, SrOddSquareDto {
  String name();

  String title();
}
