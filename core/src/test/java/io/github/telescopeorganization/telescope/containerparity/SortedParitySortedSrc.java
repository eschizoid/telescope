package io.github.telescopeorganization.telescope.containerparity;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.Set;

/** The control's source: same shape, but bridged to a target that keeps an order. */
@Bridge(SortedParitySortedTgt.class)
public record SortedParitySortedSrc(Set<SortedParityA> items) {}
