package io.github.telescopeorganization.telescope.containerparity;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Paired against a target whose only comparator constructor orders the wrong thing. */
@Bridge(EntryCmpTgt.class)
public record EntryCmpSrc(SortedMap<String, SortedParityA> items) {}
