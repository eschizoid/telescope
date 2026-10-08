package io.github.eschizoid.telescope.codegen.ctorpair;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.codegen.ctorbox.GuardedDst;
import java.util.List;

/**
 * A source in another package, which is where a bridge for it is generated.
 *
 * @param items the list
 */
@Bridge(GuardedDst.class)
public record GuardedSrc(List<String> items) {}
