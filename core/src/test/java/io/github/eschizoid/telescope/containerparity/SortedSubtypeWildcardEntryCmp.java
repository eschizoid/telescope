package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * A sorted map whose only comparator constructor orders entries, written behind the wildcard the
 * JDK uses for its own comparator parameters. Nothing a source carries can be passed to it.
 */
public class SortedSubtypeWildcardEntryCmp<K, V> extends TreeMap<K, V> {

  private static final long serialVersionUID = 1L;

  public SortedSubtypeWildcardEntryCmp() {}

  public SortedSubtypeWildcardEntryCmp(final Comparator<? super Map.Entry<K, V>> ignored) {}
}
