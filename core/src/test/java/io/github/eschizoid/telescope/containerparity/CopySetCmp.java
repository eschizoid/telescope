package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.TreeSet;

/**
 * The set counterpart of the copied map pair. A set reaches its allocator through different code
 * than a map, so a rule about the copy route holds for both families only if each is asked.
 */
public class CopySetCmp extends TreeSet<String> {

  private static final long serialVersionUID = 1L;

  public CopySetCmp() {}

  public CopySetCmp(final Comparator<? super String> c) {
    super(c);
  }
}
