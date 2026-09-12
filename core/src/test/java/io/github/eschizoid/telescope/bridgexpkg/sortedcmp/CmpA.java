package io.github.eschizoid.telescope.bridgexpkg.sortedcmp;

/** Comparable, so a naturally ordered set of these is constructible at all. */
public record CmpA(String v) implements Comparable<CmpA> {
  @Override
  public int compareTo(final CmpA other) {
    return v.compareTo(other.v);
  }
}
