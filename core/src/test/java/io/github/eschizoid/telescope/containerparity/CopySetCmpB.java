package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.TreeSet;

/** The other side: same kind, same element type, a different class. */
public class CopySetCmpB extends TreeSet<String> {

  private static final long serialVersionUID = 1L;

  public CopySetCmpB() {}

  public CopySetCmpB(final Comparator<? super String> c) {
    super(c);
  }
}
