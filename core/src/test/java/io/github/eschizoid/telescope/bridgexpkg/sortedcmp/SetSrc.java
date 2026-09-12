package io.github.eschizoid.telescope.bridgexpkg.sortedcmp;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.SortedSet;

/** Elements convert, so a comparator ordering the source's cannot order the result. */
@Bridge(SetDst.class)
public record SetSrc(SortedSet<CmpA> items) {}
