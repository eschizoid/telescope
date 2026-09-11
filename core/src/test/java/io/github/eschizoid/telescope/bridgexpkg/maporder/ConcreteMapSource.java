package io.github.eschizoid.telescope.bridgexpkg.maporder;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Fields declared as concrete classes asked for those classes rather than the interface family's
 * default, so each keeps getting the one it named — the interface-typed sibling in this package is
 * what moved.
 */
@Bridge(ConcreteMapTarget.class)
public record ConcreteMapSource(HashMap<String, Leaf> hashed, LinkedHashMap<String, Leaf> ordered) {}
