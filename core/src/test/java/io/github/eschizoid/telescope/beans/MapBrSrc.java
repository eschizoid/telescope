package io.github.eschizoid.telescope.beans;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.Map;

/** Source for the MAP concrete-subtype path: a Map source whose values need a sub-bridge. */
@Bridge(MapBrDst.class)
public record MapBrSrc(Map<String, OptElem> byKey) {}
