package io.github.eschizoid.telescope.collections;

/** An item of a {@link GLineBox}. */
public class GLine {

  private String sku;

  public GLine() {}

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
