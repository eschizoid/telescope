package io.github.eschizoid.telescope.containerparity;

import java.util.TreeMap;

/**
 * A sorted map an adopter declared, with no way to receive a comparator. Java does not inherit
 * constructors, so the one its supertype offers is not one this type has.
 */
public class SortedSubtypePlain<K, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypePlain() {}
}
