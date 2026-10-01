package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.TreeMap;

/**
 * A sorted map whose comparator constructor names a generic supertype of its keys. Any comparator
 * over the keys is one of the parameter's own arguments, so the class orders exactly as the wider
 * form does, and the parameter mentions none of the class's own variables.
 */
public class SortedSubtypeComparableCmp<K extends Comparable<?>, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypeComparableCmp() {}

  public SortedSubtypeComparableCmp(final Comparator<Comparable<?>> c) {
    super(c);
  }
}
