package io.github.telescopeorganization.telescope.sealedrows;

/** A source interface whose accessors rows name. */
public sealed interface SrShape permits SrCircle, SrSquare {
  String name();

  String label();
}
