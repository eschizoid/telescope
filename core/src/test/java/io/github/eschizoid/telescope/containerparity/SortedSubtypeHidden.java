package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.TreeMap;

/**
 * A class that is not public, carrying a public comparator constructor. The constructor's own
 * access says nothing about whether the class holding it can be reached.
 */
class SortedSubtypeHidden<K, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypeHidden() {}

  public SortedSubtypeHidden(final Comparator<? super K> c) {
    super(c);
  }
}
