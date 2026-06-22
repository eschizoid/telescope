package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.Optional;

/**
 * Codegen fixture with an {@code Optional<OptElem>} component bridged to {@code
 * Optional<OptElemBO>}. Exercises the generated Optional lift's null-guard: a null Optional
 * reference must pass through as null rather than NPE on {@code .map(...)}, matching the runtime
 * {@code Iso.liftOptional}.
 */
@Bridge(OptDst.class)
public record OptSrc(Optional<OptElem> maybe) {}
