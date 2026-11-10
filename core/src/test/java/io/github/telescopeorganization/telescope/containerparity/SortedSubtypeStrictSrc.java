package io.github.telescopeorganization.telescope.containerparity;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Source for the Strict target. */
@Bridge(SortedSubtypeStrictTgt.class)
public record SortedSubtypeStrictSrc(SortedMap<String, SortedParityA> items) {}
