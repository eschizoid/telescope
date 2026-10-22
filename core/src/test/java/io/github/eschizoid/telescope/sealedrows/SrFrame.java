package io.github.eschizoid.telescope.sealedrows;

/** A shape held through its interface and a circle held as itself. */
public record SrFrame(SrShape shape, SrCircle circle) {}
