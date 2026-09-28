package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Declared as the interface, paired against a subtype that fixed its arguments. */
@Bridge(FixedArgTgt.class)
public record FixedArgSrc(SortedMap<String, SortedParityA> items) {}
