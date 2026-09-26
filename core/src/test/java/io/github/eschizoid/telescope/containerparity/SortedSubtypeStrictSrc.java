package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Source for the Strict target. */
@Bridge(SortedSubtypeStrictTgt.class)
public record SortedSubtypeStrictSrc(SortedMap<String, SortedParityA> items) {}
