package io.github.telescopeorganization.telescope.containerparity;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Source for the ObjArg target. */
@Bridge(SortedSubtypeObjArgTgt.class)
public record SortedSubtypeObjArgSrc(SortedMap<String, SortedParityA> items) {}
