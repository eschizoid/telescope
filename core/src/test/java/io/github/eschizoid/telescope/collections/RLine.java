package io.github.eschizoid.telescope.collections;

/** A line with a property of the order type that does not point at the order holding it. */
public class RLine {

  private String sku;
  private ROrder replacedFrom;

  public RLine() {}

  public String getSku() {
    return sku;
  }

  public void setSku(final String sku) {
    this.sku = sku;
  }

  public ROrder getReplacedFrom() {
    return replacedFrom;
  }

  public void setReplacedFrom(final ROrder replacedFrom) {
    this.replacedFrom = replacedFrom;
  }

  @Override
  public String toString() {
    return sku;
  }
}
