package io.github.eschizoid.telescope.containerparity;

/**
 * The other side of the conversion. It is {@link Comparable}, as an element a sorted target
 * converts into has to be, so these fixtures reach the comparator a source carries rather than
 * stopping at an element nothing can order.
 */
public record SortedParityB(String v) implements Comparable<SortedParityB> {
  @Override
  public int compareTo(final SortedParityB other) {
    return v.compareTo(other.v());
  }
}
