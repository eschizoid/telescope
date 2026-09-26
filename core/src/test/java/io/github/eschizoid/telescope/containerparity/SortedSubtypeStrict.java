package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A subtype whose comparator constructor rejects null, which is the ordinary way to write one. A
 * rebuild that reaches for it when the source is naturally ordered fails on a value the no-argument
 * constructor would have handled.
 */
public class SortedSubtypeStrict<K, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypeStrict() {}

  public SortedSubtypeStrict(final Comparator<? super K> c) {
    super(Objects.requireNonNull(c, "comparator"));
  }
}
