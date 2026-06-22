package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Root {@code @BeanFocus} POJO of a 3-hop write path whose hop-2 intermediate is a multi-property
 * builder-strategy bean. Building this root from scratch leaves {@link #mid} (and the builder leaf
 * under it) null, so the multi-property builder rebuild runs against a {@code null} previous
 * instance.
 */
@BeanFocus
public class MultiPropBuilderOuter {

  private MultiPropBuilderMid mid;

  public MultiPropBuilderOuter() {}

  public MultiPropBuilderMid getMid() {
    return mid;
  }

  public void setMid(final MultiPropBuilderMid mid) {
    this.mid = mid;
  }
}
