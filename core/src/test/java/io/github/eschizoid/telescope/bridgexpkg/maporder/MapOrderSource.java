package io.github.eschizoid.telescope.bridgexpkg.maporder;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.Map;
import java.util.Set;

/**
 * Interface-typed container fields, so both sides pick the family default rather than a declared
 * concrete class. The Set field is the control: it has always rebuilt insertion-ordered, so it
 * holds whatever the Map field is asserted to hold.
 */
@Bridge(MapOrderTarget.class)
public record MapOrderSource(Map<String, Leaf> byKey, Set<Leaf> items) {}
