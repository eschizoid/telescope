package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedMap;

/**
 * The same pairing against a subtype that can be told, which is what makes the row above a rule.
 */
@Bridge(SortedSubtypeCmpTgt.class)
public record SortedSubtypeCmpSrc(SortedMap<String, SortedParityA> items) {}
