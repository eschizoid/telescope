package io.github.telescopeorganization.telescope.containerparity;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.SortedMap;

/**
 * The same pairing against a subtype that can be told, which is what makes the row above a rule.
 */
@Bridge(SortedSubtypeCmpTgt.class)
public record SortedSubtypeCmpSrc(SortedMap<String, SortedParityA> items) {}
