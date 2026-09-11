package io.github.eschizoid.telescope.patchfix;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Bridge source with a nested component that auto-derives a sub-bridge. */
@Bridge(NestDstRec.class)
public record NestSrcRec(String id, NInA inner) {}
