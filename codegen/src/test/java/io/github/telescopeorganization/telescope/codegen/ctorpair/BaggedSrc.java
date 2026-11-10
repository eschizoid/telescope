package io.github.telescopeorganization.telescope.codegen.ctorpair;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.codegen.ctorbox.BaggedDst;
import java.util.List;

/**
 * A source in another package, which is where a bridge for it is generated.
 *
 * @param items the list
 */
@Bridge(BaggedDst.class)
public record BaggedSrc(List<String> items) {}
