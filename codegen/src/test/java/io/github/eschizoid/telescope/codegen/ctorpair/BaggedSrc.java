package io.github.eschizoid.telescope.codegen.ctorpair;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.codegen.ctorbox.BaggedDst;
import java.util.List;

/**
 * A source in another package, which is where a bridge for it is generated.
 *
 * @param items the list
 */
@Bridge(BaggedDst.class)
public record BaggedSrc(List<String> items) {}
