package io.github.eschizoid.telescope.patchfix;

/** Bridge target with a nested component that auto-derives a sub-bridge. */
public record NestDstRec(String id, NInB inner) {}
