package io.github.telescopeorganization.telescope.patchfix;

import io.github.telescopeorganization.telescope.annotations.Bridge;

/** Bridge source with a nested component that auto-derives a sub-bridge. */
@Bridge(NestDstRec.class)
public record NestSrcRec(String id, NInA inner) {}
