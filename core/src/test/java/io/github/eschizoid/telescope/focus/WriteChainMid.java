package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

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
