package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Single-property hop-1 intermediate between {@link MultiPropWriteOuter} and the multi-property
 * {@link MultiPropLeafAddress}. Kept single-property so the multi-property off-path read (the shape
 * that previously NPE'd) is isolated to the hop-2 {@link MultiPropLeafAddress} rebuild.
 */
@BeanFocus
public class MultiPropMid {

  private MultiPropLeafAddress address;

  public MultiPropMid() {}

  public MultiPropLeafAddress getAddress() {
    return address;
  }

  public void setAddress(final MultiPropLeafAddress address) {
    this.address = address;
  }
}
