package io.github.eschizoid.telescope.beans;

/** Bean target rebuilt via no-arg ctor + setters, holding a distinct raw collection wrapper. */
public class RawColBeanDst {

  private RawColDstWrap items;

  public RawColDstWrap getItems() {
    return items;
  }

  public void setItems(final RawColDstWrap items) {
    this.items = items;
  }
}
