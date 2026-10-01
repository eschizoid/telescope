package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * A sorted map declaring a single-argument comparator constructor that orders something other than
 * its keys. It erases to the same constructor a usable one would, and no comparator a source can
 * supply fits it, so there is no route for an ordering to travel through.
 */
public class SortedSubtypeEntryCmp<K, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypeEntryCmp() {}

  public SortedSubtypeEntryCmp(final Comparator<Map.Entry<K, V>> ignored) {}
}
