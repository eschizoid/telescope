package io.github.telescopeorganization.telescope.codegen.hiddenpair;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.codegen.hiddenbox.BoxedDst;
import java.util.List;

/** A source in another package, which is where a bridge for it is emitted. */
@Bridge(BoxedDst.class)
public record HiddenPairSrc(List<String> items) {}
