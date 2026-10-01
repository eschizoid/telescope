package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Paired against a target whose comparator constructor takes no wider type than it has to. */
@Bridge(InvCmpTgt.class)
public record InvCmpSrc(SortedMap<String, SortedParityA> items) {}
