package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.TreeMap;

/**
 * A class that is not public, carrying a public comparator constructor. It is reachable from its
 * own package, where the bridge reading it is emitted.
 */
class SortedSubtypeHidden<K, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypeHidden() {}

  public SortedSubtypeHidden(final Comparator<? super K> c) {
    super(c);
  }
}
