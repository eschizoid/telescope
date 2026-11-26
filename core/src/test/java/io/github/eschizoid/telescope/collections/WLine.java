package io.github.eschizoid.telescope.collections;

/** A line of a {@link WOrder}. */
public class WLine extends WBase {

  private String sku;

  public WLine() {}

  public String getSku() {
    return sku;
  }

  public void setSku(final String sku) {
    this.sku = sku;
  }

  @Override
  public String toString() {
    return sku;
  }
}
