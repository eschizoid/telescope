package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * Codegen fixture with a plain (nullable) {@link OptElem} field bridged to an {@code
 * Optional<OptElemBO>} target — the reverse orientation of {@link OptSrc}. Exercises the
 * NULLABLE_TO_OPTIONAL backward lift, which reads the target Optional and must null-guard a null
 * Optional reference before {@code .map(...)}.
 */
@Bridge(NtoODst.class)
public record NtoOSrc(OptElem maybe) {}
