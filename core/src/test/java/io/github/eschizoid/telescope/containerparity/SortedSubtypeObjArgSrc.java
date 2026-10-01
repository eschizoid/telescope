package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Source for the ObjArg target. */
@Bridge(SortedSubtypeObjArgTgt.class)
public record SortedSubtypeObjArgSrc(SortedMap<String, SortedParityA> items) {}
