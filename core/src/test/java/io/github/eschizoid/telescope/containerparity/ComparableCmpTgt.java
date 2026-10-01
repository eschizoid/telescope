package io.github.eschizoid.telescope.containerparity;

/** Able to receive the order through a parameter written over a generic supertype. */
public record ComparableCmpTgt(SortedSubtypeComparableCmp<String, SortedParityB> items) {}
