package io.github.eschizoid.telescope.focus;

/** Middle hop of the UN-annotated {@code root → outer → mid → leaf} POJO chain. */
public class PlainChainMid {

  private PlainChainLeaf leaf;

  public PlainChainMid() {}

  public PlainChainLeaf getLeaf() {
    return leaf;
  }

  public void setLeaf(final PlainChainLeaf leaf) {
    this.leaf = leaf;
  }
}
