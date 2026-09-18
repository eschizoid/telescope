package io.github.eschizoid.telescope.containerparity;

import java.util.Set;

/** A target that keeps no order, so it needs neither an ordering nor a comparator. */
public record SortedParityPlainTgt(Set<SortedParityB> items) {}
