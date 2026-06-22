package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Single-property hop-1 intermediate between {@link MultiPropBuilderOuter} and the multi-property
 * builder-strategy {@link MultiPropBuilderLeaf}. Single-property so the off-path-read crash is
 * isolated to the hop-2 builder rebuild.
 */
@BeanFocus
public class MultiPropBuilderMid {

  private MultiPropBuilderLeaf leaf;

  public MultiPropBuilderMid() {}

  public MultiPropBuilderLeaf getLeaf() {
    return leaf;
  }

  public void setLeaf(final MultiPropBuilderLeaf leaf) {
    this.leaf = leaf;
  }
}
