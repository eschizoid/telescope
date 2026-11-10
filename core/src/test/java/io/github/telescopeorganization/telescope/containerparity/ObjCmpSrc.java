package io.github.telescopeorganization.telescope.containerparity;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Paired against a target whose comparator constructor names a supertype of the keys. */
@Bridge(ObjCmpTgt.class)
public record ObjCmpSrc(SortedMap<String, SortedParityA> items) {}
