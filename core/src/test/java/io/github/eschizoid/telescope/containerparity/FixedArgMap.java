package io.github.eschizoid.telescope.containerparity;

import java.util.TreeMap;

/**
 * A sorted map that fixes its own type arguments and declares none. This is how a domain names a
 * map it only ever uses in one shape, and it is a concrete container rather than a raw one: what
 * its element types are is written in its declaration, on its supertype.
 */
public class FixedArgMap extends TreeMap<String, SortedParityB> {

  private static final long serialVersionUID = 1L;

  public FixedArgMap() {}
}
