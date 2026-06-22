package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Root {@code @BeanFocus} POJO of a 3-hop write path whose hop-2 intermediate is a multi-property
 * bean. Building this root from scratch leaves {@link #mid} (and transitively the multi-property
 * address under it) null, so the hop-2 multi-property intermediate reaches its per-field rebuild
 * lens with a {@code null} previous instance — the exact shape that previously NPE'd on off-path
 * reads.
 */
@BeanFocus
public class MultiPropWriteOuter {

  private MultiPropMid mid;

  public MultiPropWriteOuter() {}

  public MultiPropMid getMid() {
    return mid;
  }

  public void setMid(final MultiPropMid mid) {
    this.mid = mid;
  }
}
