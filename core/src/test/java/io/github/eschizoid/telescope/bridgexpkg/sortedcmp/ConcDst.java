package io.github.eschizoid.telescope.bridgexpkg.sortedcmp;

import java.util.concurrent.ConcurrentMap;

public record ConcDst(ConcurrentMap<String, CmpB> byKey) {}
