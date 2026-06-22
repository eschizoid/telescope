package io.github.eschizoid.telescope.beans;

import java.util.Optional;

/** Optional-bridge target for {@link OptSrc}. */
public record OptDst(Optional<OptElemBO> maybe) {}
