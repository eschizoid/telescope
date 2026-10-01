package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.TreeMap;

/**
 * A sorted map whose comparator constructor names a supertype of its keys rather than the keys. Any
 * comparator over the keys is one of the parameter's own arguments, so {@code super(c)} accepts it
 * and the class orders exactly as the wider form does.
 */
public class SortedSubtypeObjectCmp<K, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypeObjectCmp() {}

  public SortedSubtypeObjectCmp(final Comparator<Object> c) {
    super(c);
  }
}
