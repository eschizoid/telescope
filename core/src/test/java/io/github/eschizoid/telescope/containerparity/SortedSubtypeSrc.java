package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedMap;

/** A sorted map whose values convert, bridged to a subtype that cannot hold its ordering. */
@Bridge(SortedSubtypePlainTgt.class)
public record SortedSubtypeSrc(SortedMap<String, SortedParityA> items) {}
