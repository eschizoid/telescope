package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.TreeMap;

/** The same shape, declaring the one constructor that can take the order its type promises. */
public class SortedSubtypeCmp<K, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypeCmp() {}

  public SortedSubtypeCmp(final Comparator<? super K> c) {
    super(c);
  }
}
