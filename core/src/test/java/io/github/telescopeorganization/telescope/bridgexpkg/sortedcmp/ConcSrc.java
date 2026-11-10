package io.github.telescopeorganization.telescope.bridgexpkg.sortedcmp;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.concurrent.ConcurrentMap;

/** The third family added to both tables, and the one no other test reaches. */
@Bridge(ConcDst.class)
public record ConcSrc(ConcurrentMap<String, CmpA> byKey) {}
