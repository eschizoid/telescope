package io.github.eschizoid.telescope.bridgexpkg.sortedcmp;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.concurrent.ConcurrentMap;

/** The third family added to both tables, and the one no other test reaches. */
@Bridge(ConcDst.class)
public record ConcSrc(ConcurrentMap<String, CmpA> byKey) {}
