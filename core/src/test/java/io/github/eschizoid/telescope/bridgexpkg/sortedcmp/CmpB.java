package io.github.eschizoid.telescope.bridgexpkg.sortedcmp;

public record CmpB(String v) implements Comparable<CmpB> {
  @Override
  public int compareTo(final CmpB other) {
    return v.compareTo(other.v);
  }
}
