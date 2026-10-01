package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Paired against a target whose only comparator constructor orders the wrong thing. */
@Bridge(EntryCmpTgt.class)
public record EntryCmpSrc(SortedMap<String, SortedParityA> items) {}
