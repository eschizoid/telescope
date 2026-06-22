package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * Reverse-order codegen fixture: the source fields are the boxed wrappers and the target fields are
 * the primitives (the mirror of {@link BridgePrimRec}). Exercises the forward null-default path —
 * forward writes the primitive target, so a null wrapper source coalesces to the primitive's JLS
 * default.
 */
@Bridge(BridgeWrapRecBO.class)
public record BridgeWrapRec(Boolean flag, Integer num, String name) {}
