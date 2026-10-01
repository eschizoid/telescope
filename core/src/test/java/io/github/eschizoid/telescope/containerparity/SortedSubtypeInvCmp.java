package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.TreeMap;

/**
 * A sorted map whose comparator constructor fixes the element type rather than accepting any
 * supertype of it. {@code super(c)} accepts it because a comparator over the key type is one of the
 * wider parameter's arguments, so the class compiles and behaves like any other.
 */
public class SortedSubtypeInvCmp<K, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypeInvCmp() {}

  public SortedSubtypeInvCmp(final Comparator<K> c) {
    super(c);
  }
}
