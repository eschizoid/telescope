package io.github.eschizoid.telescope.bridgexpkg.sortedcmp;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.TreeMap;

/** The element types differ, so the container is rebuilt rather than passed through. */
@Bridge(CmpDst.class)
public record CmpSrc(TreeMap<SortKey, CmpA> byKey) {}
