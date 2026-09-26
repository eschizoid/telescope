package io.github.eschizoid.telescope.containerparity;

import java.util.TreeMap;

/**
 * A subtype whose single-argument constructor takes {@code Object}. A comparator can be passed to
 * it, and nothing obliges it to order anything by what it receives.
 */
public class SortedSubtypeObjArg<K, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypeObjArg() {}

  public SortedSubtypeObjArg(final Object ignored) {
    super();
  }
}
