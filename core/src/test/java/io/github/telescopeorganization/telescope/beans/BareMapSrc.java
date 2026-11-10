package io.github.telescopeorganization.telescope.beans;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.Map;

/** Source with a bare Map<K,V> field — exercises the interface-family default allocation. */
@Bridge(BareMapDst.class)
public record BareMapSrc(Map<String, OptElem> byKey) {}
