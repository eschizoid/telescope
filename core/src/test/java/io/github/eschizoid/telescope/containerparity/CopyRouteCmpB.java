package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.TreeMap;

/** The other side: same kind, same element types, a different class. */
public class CopyRouteCmpB extends TreeMap<String, String> {

  private static final long serialVersionUID = 1L;

  public CopyRouteCmpB() {}

  public CopyRouteCmpB(final Comparator<? super String> c) {
    super(c);
  }
}
