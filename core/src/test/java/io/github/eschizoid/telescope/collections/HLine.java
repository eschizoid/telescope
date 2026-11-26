package io.github.eschizoid.telescope.collections;

/** A line of an {@link HOrder}. */
public class HLine {

  private String sku;

  public HLine() {}

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
