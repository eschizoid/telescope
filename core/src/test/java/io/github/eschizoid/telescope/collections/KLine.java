package io.github.eschizoid.telescope.collections;

/** A line of a {@link KOrder}. */
public class KLine {

  private String sku;

  public KLine() {}

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
