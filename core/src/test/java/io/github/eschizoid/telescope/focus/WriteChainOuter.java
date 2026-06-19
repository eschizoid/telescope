package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Outer hop of the {@code root → outer → mid → leaf} {@code @BeanFocus} write chain. The mid is
 * null at construction time; under the holder-backed structural-Iso path the descent into {@code
 * mid.leaf} must recursively auto-construct both intermediates so the leaf write lands.
 */
@BeanFocus
public class WriteChainOuter {

  private WriteChainMid mid;

  public WriteChainOuter() {}

  public WriteChainMid getMid() {
    return mid;
  }

  public void setMid(final WriteChainMid mid) {
    this.mid = mid;
  }
}
