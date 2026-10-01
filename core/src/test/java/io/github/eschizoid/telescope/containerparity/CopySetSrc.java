package io.github.eschizoid.telescope.containerparity;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * Both sides name a sorted set subtype with its element type fixed, so the pair is copied whole.
 */
@Bridge(CopySetTgt.class)
public record CopySetSrc(CopySetCmp items) {}
