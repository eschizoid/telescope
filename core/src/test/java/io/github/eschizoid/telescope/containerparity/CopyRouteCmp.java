package io.github.eschizoid.telescope.containerparity;

import java.util.Comparator;
import java.util.TreeMap;

/**
 * A sorted subtype that fixes its type arguments, which is how a domain names a map it uses in one
 * shape. Both sides of the pair below are one of these, so nothing converts and the pair is copied.
 */
public class CopyRouteCmp extends TreeMap<String, String> {

  private static final long serialVersionUID = 1L;

  public CopyRouteCmp() {}

  public CopyRouteCmp(final Comparator<? super String> c) {
    super(c);
  }
}
