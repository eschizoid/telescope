package io.github.eschizoid.telescope.sealedroots;

/** A sealed root one of whose cases is an enum. */
public sealed interface SePlan permits SeTier, SeCustom {}
