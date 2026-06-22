package io.github.eschizoid.telescope.beans;

import java.util.Optional;

/** Optional-typed target for {@link NtoOSrc} (nullable source field → Optional target field). */
public record NtoODst(Optional<OptElemBO> maybe) {}
