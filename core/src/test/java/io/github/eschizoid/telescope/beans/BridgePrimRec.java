package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * Codegen fixture: record&harr;record bridge whose fields are primitives ({@code boolean}, {@code
 * int}) against the boxed wrappers on {@link BridgePrimRecBO}. Exercises the auto box/unbox
 * identity path in the generated bridge.
 */
@Bridge(BridgePrimRecBO.class)
public record BridgePrimRec(boolean locked, int count, String name) {}
