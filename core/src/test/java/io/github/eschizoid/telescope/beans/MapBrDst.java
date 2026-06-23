package io.github.eschizoid.telescope.beans;

import java.util.TreeMap;

/** Target with a concrete TreeMap field; values bridge OptElem -> OptElemBO, keys preserved. */
public record MapBrDst(TreeMap<String, OptElemBO> byKey) {}
