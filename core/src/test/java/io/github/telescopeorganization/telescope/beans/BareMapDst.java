package io.github.telescopeorganization.telescope.beans;

import java.util.Map;

/** Target with a bare Map<K,V> field; values bridge OptElem -> OptElemBO. */
public record BareMapDst(Map<String, OptElemBO> byKey) {}
