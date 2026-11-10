package io.github.telescopeorganization.telescope.focus;

import io.github.telescopeorganization.telescope.annotations.BeanFocus;

/** Middle hop of the {@code root → outer → mid → leaf} {@code @BeanFocus} write chain. */
@BeanFocus
public class WriteChainMid {

  private WriteChainLeaf leaf;

  public WriteChainMid() {}

  public WriteChainLeaf getLeaf() {
    return leaf;
  }

  public void setLeaf(final WriteChainLeaf leaf) {
    this.leaf = leaf;
  }
}
