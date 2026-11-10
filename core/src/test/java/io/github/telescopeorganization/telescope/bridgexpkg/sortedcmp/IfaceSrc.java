package io.github.telescopeorganization.telescope.bridgexpkg.sortedcmp;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.SortedMap;

/** Declares the interface rather than a concrete class, which is what the family table decides. */
@Bridge(IfaceDst.class)
public record IfaceSrc(SortedMap<SortKey, CmpA> byKey) {}
