package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * Both sides name a sorted subtype with its type arguments fixed, so nothing converts and the pair
 * is copied wholesale. That route allocates from a supplier handed no source, which is a way to
 * lose an ordering without any element being touched.
 */
@Bridge(CopyRouteTgt.class)
public record CopyRouteSrc(CopyRouteCmp items) {}
