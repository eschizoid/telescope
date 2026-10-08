package io.github.eschizoid.telescope.codegen.hiddenpair;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.codegen.hiddenbox.BoxedDst;
import java.util.List;

/** A source in another package, which is where a bridge for it is emitted. */
@Bridge(BoxedDst.class)
public record HiddenPairSrc(List<String> items) {}
