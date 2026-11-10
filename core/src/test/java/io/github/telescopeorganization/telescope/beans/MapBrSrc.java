package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.Map;

/** Source for the MAP concrete-subtype path: a Map source whose values need a sub-bridge. */
@Bridge(MapBrDst.class)
public record MapBrSrc(Map<String, OptElem> byKey) {}
