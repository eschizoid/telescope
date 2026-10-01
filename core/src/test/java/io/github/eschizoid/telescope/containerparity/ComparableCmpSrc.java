package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Paired against a target whose comparator parameter names a generic supertype of the keys. */
@Bridge(ComparableCmpTgt.class)
public record ComparableCmpSrc(SortedMap<String, SortedParityA> items) {}
