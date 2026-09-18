package io.github.eschizoid.telescope.containerparity;

import java.util.SortedSet;

/** A target that does keep an order, and so does need one it can use. */
public record SortedParitySortedTgt(SortedSet<SortedParityB> items) {}
