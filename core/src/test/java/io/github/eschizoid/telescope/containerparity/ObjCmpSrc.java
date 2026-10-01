package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Paired against a target whose comparator constructor names a supertype of the keys. */
@Bridge(ObjCmpTgt.class)
public record ObjCmpSrc(SortedMap<String, SortedParityA> items) {}
