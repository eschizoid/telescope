package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Source for the Hidden target. */
@Bridge(SortedSubtypeHiddenTgt.class)
public record SortedSubtypeHiddenSrc(SortedMap<String, SortedParityA> items) {}
