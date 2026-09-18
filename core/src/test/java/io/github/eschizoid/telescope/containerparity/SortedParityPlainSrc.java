package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.Set;

/** Declared as a plain {@code Set}, which may still hold a sorted one at run time. */
@Bridge(SortedParityPlainTgt.class)
public record SortedParityPlainSrc(Set<SortedParityA> items) {}
